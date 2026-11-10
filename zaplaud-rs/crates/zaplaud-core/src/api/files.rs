use super::HabblaudApi;
use crate::Error;
use crate::model::{FileListing, FileRoot};
use serde::Deserialize;
use std::path::Path;
use tokio::io::AsyncWriteExt;

#[derive(Deserialize)]
struct FileRoots {
    roots: Vec<FileRoot>,
}

impl HabblaudApi {
    pub async fn files_roots(&self) -> Result<Vec<FileRoot>, Error> {
        let response = self.request("/api/files", None).await?;
        Ok(serde_json::from_str::<FileRoots>(&response)?.roots)
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
