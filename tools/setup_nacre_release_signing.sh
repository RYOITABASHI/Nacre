#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
SIGNING_DIR="${NACRE_SIGNING_DIR:-"${HOME}/.nacre"}"
KEYSTORE="${NACRE_RELEASE_STORE_FILE:-"${SIGNING_DIR}/nacre-release.jks"}"
SIGNING_HOME_PROPERTIES="${NACRE_SIGNING_PROPERTIES:-"${SIGNING_DIR}/nacre-release-signing.properties"}"
REPO_SIGNING_PROPERTIES="${ROOT_DIR}/signing.properties"
KEY_ALIAS="${NACRE_RELEASE_KEY_ALIAS:-nacre}"

random_password() {
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -base64 24 | tr -d '\n'
    else
        python3 -c 'import secrets; print(secrets.token_urlsafe(24))'
    fi
}

mkdir -p "${SIGNING_DIR}"
chmod 700 "${SIGNING_DIR}"

if [[ -f "${SIGNING_HOME_PROPERTIES}" ]]; then
    cp "${SIGNING_HOME_PROPERTIES}" "${REPO_SIGNING_PROPERTIES}"
    chmod 600 "${REPO_SIGNING_PROPERTIES}"
    echo "Configured Nacre release signing from ${SIGNING_HOME_PROPERTIES}"
    exit 0
fi

if [[ -f "${KEYSTORE}" ]]; then
    if [[ -n "${NACRE_RELEASE_STORE_PASSWORD:-}" ]]; then
        EXISTING_KEY_PASSWORD="${NACRE_RELEASE_KEY_PASSWORD:-${NACRE_RELEASE_STORE_PASSWORD}}"
        cat > "${SIGNING_HOME_PROPERTIES}" <<EOF
storeFile=${KEYSTORE}
storePassword=${NACRE_RELEASE_STORE_PASSWORD}
keyAlias=${KEY_ALIAS}
keyPassword=${EXISTING_KEY_PASSWORD}
EOF
        chmod 600 "${SIGNING_HOME_PROPERTIES}"
        cp "${SIGNING_HOME_PROPERTIES}" "${REPO_SIGNING_PROPERTIES}"
        chmod 600 "${REPO_SIGNING_PROPERTIES}"
        echo "Configured Nacre release signing for existing ${KEYSTORE}"
        exit 0
    fi

    cat >&2 <<EOF
Found ${KEYSTORE}, but ${SIGNING_HOME_PROPERTIES} is missing.
Provide NACRE_RELEASE_STORE_PASSWORD and NACRE_RELEASE_KEY_PASSWORD, or move the old keystore aside and rerun this script.
EOF
    exit 1
fi

STORE_PASSWORD="${NACRE_RELEASE_STORE_PASSWORD:-$(random_password)}"
KEY_PASSWORD="${STORE_PASSWORD}"

keytool -genkeypair \
    -v \
    -keystore "${KEYSTORE}" \
    -storepass "${STORE_PASSWORD}" \
    -keypass "${KEY_PASSWORD}" \
    -alias "${KEY_ALIAS}" \
    -keyalg RSA \
    -keysize 4096 \
    -validity 10000 \
    -dname "CN=Nacre Local Release,O=Nacre,C=JP"

cat > "${SIGNING_HOME_PROPERTIES}" <<EOF
storeFile=${KEYSTORE}
storePassword=${STORE_PASSWORD}
keyAlias=${KEY_ALIAS}
keyPassword=${KEY_PASSWORD}
EOF
chmod 600 "${SIGNING_HOME_PROPERTIES}"

cp "${SIGNING_HOME_PROPERTIES}" "${REPO_SIGNING_PROPERTIES}"
chmod 600 "${REPO_SIGNING_PROPERTIES}"

cat <<EOF
Created Nacre release signing key:
  ${KEYSTORE}

Configured ignored repo signing file:
  ${REPO_SIGNING_PROPERTIES}

Use this for normal update installs:
  ./gradlew installNacre
EOF
