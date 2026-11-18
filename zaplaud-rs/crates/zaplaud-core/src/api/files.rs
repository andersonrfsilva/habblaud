use super::{HabblaudApi, UrlPolicy};
use crate::Error;
use crate::model::{FileListing, FileRoot};
use serde::Deserialize;
use std::path::Path;
use tokio::io::AsyncWriteExt;

#[derive(Deserialize)]
struct FileRoots {
    roots: Vec<FileRoot>,
}

/// Limite por imagem mostrada na conversa.
pub const MAX_IMAGE_BYTES: usize = 10 * 1024 * 1024;

impl HabblaudApi {
    pub async fn files_roots(&self) -> Result<Vec<FileRoot>, Error> {
        let response = self.request("/api/files", None).await?;
        Ok(serde_json::from_str::<FileRoots>(&response)?.roots)
    }

    /// Imagem do host citada na conversa: mesma rota e mesma trava do navegador de arquivos.
    pub async fn files_image(&self, path: &str) -> Result<Vec<u8>, Error> {
        let url = self.url(&format!("/api/files/raw?path={}", super::encode_name(path)))?;
        let request = self.client.get(url.clone());
        let response = self.checked(self.send(request, &url, true).await?).await?;
        self.bytes(response, MAX_IMAGE_BYTES).await
    }

    /// Imagem de outro site, pedida pelo usuário: cliente próprio, sem cookies nem credenciais do Habblaud.
    pub async fn remote_image(url: &str) -> Result<Vec<u8>, Error> {
        let url = UrlPolicy::remote_image(url).ok_or(Error::InvalidResponse("URL da imagem"))?;
        let client = reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(30))
            .redirect(reqwest::redirect::Policy::none())
            .build()?;
        let response = client.get(url).send().await?;
        if response.status().is_redirection() {
            return Err(Error::Redirect(response.status().as_u16()));
        }
        let mut response = response.error_for_status()?;
        let mut bytes = Vec::new();
        while let Some(chunk) = response.chunk().await? {
            if chunk.len() > MAX_IMAGE_BYTES.saturating_sub(bytes.len()) {
                return Err(Error::ResponseTooLarge);
            }
            bytes.extend_from_slice(&chunk);
        }
        Ok(bytes)
    }

    pub async fn files_list(&self, path: &str) -> Result<FileListing, Error> {
        let route = format!("/api/files/list?path={}", super::encode_name(path));
        Ok(serde_json::from_str(&self.request(&route, None).await?)?)
    }

    /// Baixa sem limite de tamanho; só publica o destino completo, sem sobrescrever outro arquivo.
    /// Descartar a espera ou receber um erro remove o temporário irmão do destino.
    pub async fn files_download<F>(
        &self,
        path: &str,
        destination: &Path,
        mut progress: F,
    ) -> Result<(), Error>
    where
        F: FnMut(u64, Option<u64>) + Send,
    {
        let route = format!(
            "/api/files/raw?path={}&download=1",
            super::encode_name(path)
        );
        let url = self.url(&route)?;
        let request = self.client.get(url.clone());
        let mut response = self.checked(self.send(request, &url, true).await?).await?;
        let total = response.content_length();
        let mut cancellation = response.cancellation.clone();
        let parent = destination
            .parent()
            .filter(|parent| !parent.as_os_str().is_empty())
            .unwrap_or_else(|| Path::new("."))
            .to_owned();
        let temporary = tokio::task::spawn_blocking(move || {
            tempfile::Builder::new()
                .prefix(".zaplaud-download-")
                .tempfile_in(parent)
        })
        .await
        .map_err(|_| Error::SessionEnded)??;
        let (file, temporary) = temporary.into_parts();
        let mut file = tokio::fs::File::from_std(file);
        let mut received = 0_u64;
        progress(received, total);
        loop {
            let chunk = tokio::select! {
                biased;
                _ = cancellation.changed() => return Err(Error::SessionEnded),
                chunk = response.chunk() => chunk?,
            };
            let Some(chunk) = chunk else {
                break;
            };
            tokio::select! {
                biased;
                _ = cancellation.changed() => return Err(Error::SessionEnded),
                result = file.write_all(&chunk) => result?,
            }
            received += chunk.len() as u64;
            progress(received, total);
        }
        tokio::select! {
            biased;
            _ = cancellation.changed() => return Err(Error::SessionEnded),
            result = file.flush() => result?,
        }
        drop(file);
        if cancellation.has_changed().unwrap_or(true) {
            return Err(Error::SessionEnded);
        }
        // O no-clobber protege também contra um destino criado depois da seleção na interface.
        temporary
            .persist_noclobber(destination)
            .map_err(|error| error.error)?;
        Ok(())
    }
}
