// A API e a UI usam a mesma validação, sem segunda implementação.
pub use crate::api::UrlPolicy;

pub fn origin(input: &str) -> Option<String> {
    UrlPolicy::origin(input)
}
