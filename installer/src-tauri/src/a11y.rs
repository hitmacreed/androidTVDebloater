//! Helpers for the enabled-accessibility-services list (`settings get secure
//! enabled_accessibility_services`), which is a colon-separated string.

pub const SERVICE: &str =
    "com.hitmacreed.androidtvdebloater/com.hitmacreed.androidtvdebloater.HomeKeyService";

// Android accepts the short form too, and the list may hold either spelling.
const SHORT_FORM: &str = "com.hitmacreed.androidtvdebloater/.HomeKeyService";

// The service under the launcher's package from before the androidTVDebloater rename. It is
// dropped too, so an upgraded TV doesn't keep pointing at an app that no longer exists.
const OLD_FORMS: [&str; 2] = [
    "com.gathin.tvhome/com.gathin.tvhome.HomeKeyService",
    "com.gathin.tvhome/.HomeKeyService",
];

pub fn with_service(current: &str) -> String {
    let mut entries = others(current);
    entries.push(SERVICE);
    entries.join(":")
}

pub fn without_service(current: &str) -> String {
    others(current).join(":")
}

fn others(current: &str) -> Vec<&str> {
    current
        .split(':')
        .filter(|e| {
            !e.is_empty()
                && *e != "null"
                && *e != SERVICE
                && *e != SHORT_FORM
                && !OLD_FORMS.contains(e)
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn add_to_null() {
        assert_eq!(with_service("null"), SERVICE);
    }
    #[test]
    fn add_to_empty() {
        assert_eq!(with_service(""), SERVICE);
    }
    #[test]
    fn add_keeps_others() {
        assert_eq!(with_service("x/.A"), format!("x/.A:{SERVICE}"));
    }
    #[test]
    fn add_is_idempotent() {
        assert_eq!(with_service(SERVICE), SERVICE);
    }
    #[test]
    fn add_replaces_short_form() {
        assert_eq!(
            with_service("com.hitmacreed.androidtvdebloater/.HomeKeyService"),
            SERVICE
        );
    }
    #[test]
    fn remove_keeps_others() {
        assert_eq!(
            without_service(&format!("x/.A:{SERVICE}:y/.B")),
            "x/.A:y/.B"
        );
    }
    #[test]
    fn remove_short_form() {
        assert_eq!(
            without_service("com.hitmacreed.androidtvdebloater/.HomeKeyService:x/.A"),
            "x/.A"
        );
    }
    #[test]
    fn add_drops_old_package_service() {
        assert_eq!(
            with_service("com.gathin.tvhome/.HomeKeyService:x/.A"),
            format!("x/.A:{SERVICE}")
        );
    }
    #[test]
    fn remove_drops_old_package_service() {
        assert_eq!(
            without_service("com.gathin.tvhome/com.gathin.tvhome.HomeKeyService:x/.A"),
            "x/.A"
        );
    }
    #[test]
    fn remove_from_null() {
        assert_eq!(without_service("null"), "");
    }
}
