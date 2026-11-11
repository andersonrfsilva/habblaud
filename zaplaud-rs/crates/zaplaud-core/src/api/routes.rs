use super::*;
use crate::model::{self, *};
use serde::Serialize;
use std::collections::BTreeMap;
use tokio::io::{AsyncRead, AsyncReadExt};

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MessageReceipt {
    pub id: String,
    pub status: Option<String>,
    pub error: Option<String>,
}
impl MessageReceipt {
    fn parse(value: &Value, require_id: bool, require_status: bool) -> Result<Self, Error> {
        let id = model::string(value, "id");
        let status = model::string(value, "status");
        if require_id && id.is_none() {
            return Err(Error::InvalidResponse("id"));
        }
        if require_status && status.is_none() {
            return Err(Error::InvalidResponse("status"));
        }
        Ok(Self {
            id: id.unwrap_or_default(),
            status,
            error: model::string(value, "error"),
        })
    }
}
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SendMessage<'a> {
    pub agent_id: &'a str,
    pub text: &'a str,
    pub expected_session_id: &'a str,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub deliver_as: Option<SendMode>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub attachments: Vec<String>,
}
#[derive(Clone, Debug, Serialize)]
#[serde(untagged)]
pub enum Control {
    Stop { stop: bool },
    Shake { command: String },
    Model { model: String },
    Thinking { thinking: String },
}
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ControlMessage<'a> {
    pub agent_id: &'a str,
    pub expected_session_id: &'a str,
    #[serde(flatten)]
    pub control: Control,
}
#[derive(Clone, Debug, Serialize)]
pub struct ResumeSession<'a> {
    pub session: &'a str,
    pub account: &'a str,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CastSubmission {
    pub cast: Option<String>,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CastMember {
    pub choice: CastChoice,
    pub account: Option<String>,
    pub last: BTreeMap<String, String>,
}
#[derive(Clone, Debug, Serialize)]
pub struct QuestionAnswer {
    pub question: i32,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub options: Vec<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub other: Option<String>,
}
#[derive(Clone, Debug, Serialize)]
#[serde(tag = "behavior", rename_all = "lowercase")]
pub enum PermissionDecision {
    Allow {
        #[serde(skip_serializing_if = "Option::is_none")]
        suggestion: Option<i32>,
    },
    Deny {
        #[serde(skip_serializing_if = "Option::is_none")]
        message: Option<String>,
        #[serde(skip_serializing_if = "std::ops::Not::not")]
        interrupt: bool,
    },
    Terminal,
    Answer {
        answers: Vec<QuestionAnswer>,
    },
}

pub fn encode_segment(value: &str) -> String {
    encode_name(value)
}
/// Mesmo conjunto literal de encodeAttachmentName do Kotlin.
pub fn encode_name(value: &str) -> String {
    use std::fmt::Write;
    let mut encoded = String::new();
    for byte in value.bytes() {
        if byte.is_ascii_alphanumeric() || b"-_.!~*'()".contains(&byte) {
            encoded.push(char::from(byte));
        } else {
            let _ = write!(encoded, "%{byte:02X}");
        }
    }
    encoded
}

impl HabblaudApi {
    async fn json(&self, path: &str, body: Option<Value>) -> Result<Value, Error> {
        Ok(serde_json::from_str(
            &self.request(path, body.as_ref()).await?,
        )?)
    }
    pub async fn access(&self) -> Result<Access, Error> {
        Ok(Access::parse(&self.json("/api/access", None).await?))
    }
    pub async fn snapshot(&self) -> Result<Snapshot, Error> {
        Ok(Snapshot::parse(
            self.origin(),
            &self.request("/api/snapshot", None).await?,
        )?)
    }
    pub async fn recent_sessions(&self) -> Result<Vec<SessionItem>, Error> {
        let value = self.json("/api/sessions/recent", None).await?;
        Ok(model::objects(&value, "sessions")
            .filter_map(|o| SessionItem::parse(self.origin(), o, None, 0, 0))
            .collect())
    }
    pub async fn cast(&self) -> Result<Vec<CastMember>, Error> {
        let value = self.json("/api/cast", None).await?;
        Ok(model::objects(&value, "members")
            .filter_map(|o| {
                Some(CastMember {
                    choice: CastChoice::parse(o)?,
                    account: model::string(o, "account"),
                    last: o
                        .get("last")
                        .and_then(Value::as_object)
                        .into_iter()
                        .flatten()
                        .filter_map(|(k, _)| Some((k.clone(), model::string(&o["last"], k)?)))
                        .collect(),
                })
            })
            .collect())
    }
    pub async fn cast_folders(&self) -> Result<Vec<String>, Error> {
        let value = self.json("/api/cast/folders", None).await?;
        Ok(model::objects(&value, "folders")
            .filter_map(|o| model::string(o, "path"))
            .collect())
    }
    pub async fn cast_jobs(&self) -> Result<CastJobs, Error> {
        Ok(CastJobs::parse(&self.json("/api/cast/jobs", None).await?))
    }
    pub async fn roles(&self) -> Result<ModelRoles, Error> {
        Ok(ModelRoles::parse(
            &self.json("/api/cast/roles", None).await?,
        ))
    }
    pub async fn set_roles(&self, roles: &BTreeMap<String, String>) -> Result<(), Error> {
        self.request("/api/cast/roles", Some(&json!({"roles":roles})))
            .await
            .map(|_| ())
    }
    async fn submit_cast(&self, path: &str, body: Value) -> Result<CastSubmission, Error> {
        let value = self.json(path, Some(body)).await?;
        Ok(CastSubmission {
            cast: value.get("job").and_then(|job| model::string(job, "cast")),
        })
    }
    pub async fn cast_runtime(&self) -> Result<Option<String>, Error> {
        let value = self.json("/api/cast/models", None).await?;
        Ok(model::catalog_runtime(&value).map(str::to_owned))
    }
    pub async fn summary_http(&self) -> Result<bool, Error> {
        Ok(self.json("/api/health", None).await?.get("summaryHttp") == Some(&Value::Bool(true)))
    }
    pub async fn new_cast(
        &self,
        room: &str,
        resume: Option<ResumeSession<'_>>,
    ) -> Result<CastSubmission, Error> {
        let mut body = json!({"room":room});
        if let Some(resume) = resume {
            body["session"] = json!(resume.session);
            body["account"] = json!(resume.account);
        }
        self.submit_cast("/api/cast/new", body).await
    }
    pub async fn start_cast(
        &self,
        cast_id: &str,
        room: &str,
        resume: Option<ResumeSession<'_>>,
    ) -> Result<CastSubmission, Error> {
        let mut body = json!({"room":room,"mode":if resume.is_some() {"resume"} else {"new"}});
        if let Some(resume) = resume {
            body["session"] = json!(resume.session);
            body["account"] = json!(resume.account);
        }
        self.submit_cast(
            &format!("/api/cast/{}/start", encode_segment(cast_id)),
            body,
        )
        .await
    }
    pub async fn dismiss_agent(&self, agent_id: &str) -> Result<CastSubmission, Error> {
        self.submit_cast(
            &format!("/api/agents/{}/dismiss", encode_segment(agent_id)),
            json!({}),
        )
        .await
    }
    pub async fn send_message(&self, message: &SendMessage<'_>) -> Result<MessageReceipt, Error> {
        MessageReceipt::parse(
            &self
                .json("/api/messages", Some(serde_json::to_value(message)?))
                .await?,
            true,
            true,
        )
    }
    pub async fn control_message(
        &self,
        message: &ControlMessage<'_>,
    ) -> Result<MessageReceipt, Error> {
        MessageReceipt::parse(
            &self
                .json(
                    "/api/messages/control",
                    Some(serde_json::to_value(message)?),
                )
                .await?,
            true,
            matches!(message.control, Control::Stop { .. }),
        )
    }
    pub async fn message_status(&self, id: &str) -> Result<MessageReceipt, Error> {
        MessageReceipt::parse(
            &self
                .json(&format!("/api/messages/{}", encode_segment(id)), None)
                .await?,
            false,
            true,
        )
    }
    /// A lista de permissões vem do snapshot; não existe GET /api/permissions no cliente Kotlin.
    pub async fn permissions(&self) -> Result<Vec<(SessionKey, String)>, Error> {
        Ok(self
            .snapshot()
            .await?
            .sessions
            .into_iter()
            .filter_map(|s| Some((s.key, s.permission_id?)))
            .collect())
    }
    pub async fn permission(&self, id: &str) -> Result<Permission, Error> {
        Ok(Permission::parse(
            &self
                .json(&format!("/api/permissions/{}", encode_segment(id)), None)
                .await?,
        ))
    }
    pub async fn decide_permission(
        &self,
        id: &str,
        decision: &PermissionDecision,
    ) -> Result<(), Error> {
        self.request(
            &format!("/api/permissions/{}/decision", encode_segment(id)),
            Some(&serde_json::to_value(decision)?),
        )
        .await
        .map(|_| ())
    }
    /// Envia o JSON já validado pelas regras puras, sem duplicar o validador da folha de permissão.
    pub async fn decide_permission_json(&self, id: &str, decision: &Value) -> Result<(), Error> {
        self.request(
            &format!("/api/permissions/{}/decision", encode_segment(id)),
            Some(decision),
        )
        .await
        .map(|_| ())
    }
    pub async fn asides(&self, agent_id: &str) -> Result<Vec<AsideTopic>, Error> {
        let value = self
            .json(
                &format!("/api/asides?agentId={}", encode_segment(agent_id)),
                None,
            )
            .await?;
        Ok(model::objects(&value, "topics")
            .filter_map(AsideTopic::parse)
            .collect())
    }
    /// Asides usam polling HTTP de 700 ms, não SSE; encerra após erro ou quando nenhum turno está ativo.
    pub fn aside_stream(
        &self,
        agent_id: &str,
    ) -> impl futures_util::Stream<Item = Result<Vec<AsideTopic>, Error>> + Send + 'static {
        let api = self.clone();
        let agent_id = agent_id.to_owned();
        futures_util::stream::unfold(
            (api, agent_id, false, false),
            |(api, agent_id, wait, ended)| async move {
                if ended {
                    return None;
                }
                if wait {
                    tokio::time::sleep(std::time::Duration::from_millis(700)).await;
                }
                let result = api.asides(&agent_id).await;
                let ended = result.as_ref().map_or(true, |topics| {
                    !topics
                        .iter()
                        .flat_map(|topic| &topic.turns)
                        .any(AsideTurn::running)
                });
                Some((result, (api, agent_id, true, ended)))
            },
        )
    }
    pub async fn ask_aside(
        &self,
        agent_id: &str,
        question: &str,
        topic_id: Option<&str>,
    ) -> Result<Option<AsideTopic>, Error> {
        let mut body = json!({"agentId":agent_id,"question":question.trim()});
        if let Some(id) = topic_id {
            body["topicId"] = json!(id);
        }
        let value = self.json("/api/asides", Some(body)).await?;
        Ok(value.get("topic").and_then(AsideTopic::parse))
    }
    pub async fn cancel_aside(&self, agent_id: &str, topic_id: &str) -> Result<(), Error> {
        self.request(
            &format!("/api/asides/{}/cancel", encode_segment(topic_id)),
            Some(&json!({"agentId":agent_id})),
        )
        .await
        .map(|_| ())
    }
    /// Preserva o corte UTF-16 Kotlin, inclusive um surrogate final isolado.
    pub async fn summary_utf16(&self, text: &[u16]) -> Result<String, Error> {
        use std::fmt::Write;
        let mut body = String::with_capacity(text.len() * 6 + 11);
        body.push_str("{\"text\":\"");
        for unit in text {
            let _ = write!(body, "\\u{unit:04x}");
        }
        body.push_str("\"}");
        let url = self.url("/api/summaries")?;
        let request = self
            .client
            .post(url.clone())
            .header(CONTENT_TYPE, "application/json; charset=utf-8")
            .body(body);
        let response = self.checked(self.send(request, &url, false).await?).await?;
        let value: Value = serde_json::from_slice(&self.bytes(response, MAX_EVENT_BYTES).await?)?;
        match value.get("summary") {
            Some(Value::String(text)) => Ok(text.clone()),
            Some(value @ (Value::Number(_) | Value::Bool(_) | Value::Null)) => {
                Ok(value.to_string())
            }
            _ => Err(Error::InvalidResponse("summary")),
        }
    }
    pub async fn summary(&self, text: &str) -> Result<String, Error> {
        let value = self
            .json("/api/summaries", Some(json!({"text":text})))
            .await?;
        match value.get("summary") {
            Some(Value::String(text)) => Ok(text.clone()),
            Some(value @ (Value::Number(_) | Value::Bool(_) | Value::Null)) => {
                Ok(value.to_string())
            }
            _ => Err(Error::InvalidResponse("summary")),
        }
    }
    /// Quotas e contexto são campos de snapshot, não rotas adicionais.
    pub async fn quotas(&self) -> Result<Vec<AccountQuota>, Error> {
        Ok(self.snapshot().await?.accounts)
    }
    pub async fn avatar_bytes(&self, avatar: &Avatar) -> Result<Option<Vec<u8>>, Error> {
        let mut url = self.url("/api/avatar.png")?;
        {
            let mut query = url.query_pairs_mut();
            query
                .append_pair("seed", &avatar.seed.to_string())
                .append_pair("look", &avatar.look)
                .append_pair("kind", &avatar.kind)
                .append_pair("scale", "3");
            if let Some(parts) = &avatar.parts {
                query.append_pair("parts", parts);
            }
        }
        self.avatar_path_bytes(&format!("{}?{}", url.path(), url.query().unwrap_or("")))
            .await
    }
    pub async fn avatar_path_bytes(&self, path: &str) -> Result<Option<Vec<u8>>, Error> {
        let url = self.url(path)?;
        let response = self.send(self.client.get(url.clone()), &url, true).await?;
        if !response.status().is_success()
            || !response
                .headers()
                .get(CONTENT_TYPE)
                .and_then(|v| v.to_str().ok())
                .and_then(|v| v.split(';').next())
                .is_some_and(|v| v.trim().eq_ignore_ascii_case("image/png"))
        {
            return Ok(None);
        }
        match self.bytes(response, 1024 * 1024).await {
            Ok(bytes) => Ok(Some(bytes)),
            Err(Error::ResponseTooLarge) => Ok(None),
            Err(e) => Err(e),
        }
    }
    /// Fonte lida em blocos de 8192 bytes; nenhuma cópia integral do arquivo.
    pub async fn upload<R, F>(
        &self,
        name: &str,
        mime: &str,
        reader: R,
        size: Option<u64>,
        progress: F,
    ) -> Result<String, Error>
    where
        R: AsyncRead + Send + Unpin + 'static,
        F: FnMut(u64) + Send + 'static,
    {
        if size.is_some_and(|n| n == 0 || n > MAX_ATTACHMENT_SIZE as u64) {
            return Err(Error::InvalidResponse("tamanho do anexo"));
        }
        let stream = futures_util::stream::try_unfold(
            (reader, progress, 0_u64),
            move |(mut reader, mut progress, count)| async move {
                let mut bytes = vec![0; 8192];
                let read = reader.read(&mut bytes).await?;
                let next = count + read as u64;
                if next > MAX_ATTACHMENT_SIZE as u64 || size.is_some_and(|size| next > size) {
                    return Err(std::io::Error::new(
                        std::io::ErrorKind::InvalidData,
                        "anexo excede tamanho",
                    ));
                }
                if read == 0 {
                    if count == 0 || size.is_some_and(|size| count != size) {
                        return Err(std::io::Error::new(
                            std::io::ErrorKind::UnexpectedEof,
                            "tamanho do anexo mudou",
                        ));
                    }
                    return Ok(None);
                }
                // Hyper pode parar de pedir blocos ao atingir Content-Length: valide EOF antes do último bloco.
                if size == Some(next) && reader.read(&mut [0_u8; 1]).await? != 0 {
                    return Err(std::io::Error::new(
                        std::io::ErrorKind::InvalidData,
                        "tamanho do anexo mudou",
                    ));
                }
                bytes.truncate(read);
                progress(next);
                Ok(Some((bytes, (reader, progress, next))))
            },
        );
        let url = self.url("/api/messages/attachments")?;
        let mut request = self
            .client
            .post(url.clone())
            .header(CONTENT_TYPE, "application/octet-stream")
            .header("X-Habblaud-File-Name", encode_name(name))
            .header("X-Habblaud-File-Type", mime)
            .body(reqwest::Body::wrap_stream(stream));
        if let Some(size) = size {
            request = request.header(reqwest::header::CONTENT_LENGTH, size);
        }
        let response = self.checked(self.send(request, &url, false).await?).await?;
        let bytes = self.bytes(response, MAX_EVENT_BYTES).await?;
        model::string(&serde_json::from_slice(&bytes)?, "id").ok_or(Error::InvalidResponse("id"))
    }
}
