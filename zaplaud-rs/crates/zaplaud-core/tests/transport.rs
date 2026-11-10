use std::time::Duration;

use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::TcpListener;
use tokio::time::timeout;
use zaplaud_core::{Error, api::HabblaudApi};

#[tokio::test]
async fn redirects_are_refused_without_following_location() -> Result<(), Box<dyn std::error::Error>>
{
    let listener = TcpListener::bind("127.0.0.1:0").await?;
    let address = listener.local_addr()?;
    let server = tokio::spawn(async move {
        let (mut socket, _) = listener.accept().await?;
        let mut request = BufReader::new(&mut socket);
        let mut line = String::new();
        while request.read_line(&mut line).await? != 0 {
            if line == "\r\n" {
                break;
            }
            line.clear();
        }
        socket.write_all(b"HTTP/1.1 302 Found\r\nLocation: /api/redirected\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").await?;
        socket.shutdown().await?;
        // Um cliente que segue Location faria uma segunda conexão.
        assert!(
            timeout(Duration::from_millis(100), listener.accept())
                .await
                .is_err()
        );
        Ok::<_, std::io::Error>(())
    });
    let api = HabblaudApi::new(&format!("http://{address}"))?;
    assert!(matches!(
        api.request("/api/snapshot", None).await,
        Err(Error::Redirect(302))
    ));
    server.await??;
    Ok(())
}

#[tokio::test]
async fn invalid_paths_never_make_network_requests() -> Result<(), Error> {
    let api = HabblaudApi::new("http://127.0.0.1:1")?;
    for path in [
        "https://example.com/api/snapshot",
        "//example.com/api/snapshot",
        "/other",
        "/api/\\\\example.com",
        "/api/snapshot#fragment",
    ] {
        assert!(matches!(
            api.request(path, None).await,
            Err(Error::InvalidPath)
        ));
    }
    Ok(())
}
