// These conversion helpers also support the administrator's Stop Server confirmation.
function log(message, state = 'info') {
  const status = document.getElementById('log');
  if (!status) return;
  status.textContent = message;
  status.dataset.state = state;
  status.hidden = !message;
}

    // --- Helpers: base64url <-> ArrayBuffer -----------------------------------

    function base64urlToUint8Array(base64urlString) {
      const padding = '='.repeat((4 - base64urlString.length % 4) % 4);
      const base64 = (base64urlString + padding)
        .replace(/-/g, '+')
        .replace(/_/g, '/');
      const rawData = atob(base64);
      const outputArray = new Uint8Array(rawData.length);
      for (let i = 0; i < rawData.length; ++i) {
        outputArray[i] = rawData.charCodeAt(i);
      }
      return outputArray;
    }

    function arrayBufferToBase64url(buffer) {
      const bytes = new Uint8Array(buffer);
      let binary = '';
      for (let i = 0; i < bytes.length; i++) {
        binary += String.fromCharCode(bytes[i]);
      }
      const base64 = btoa(binary);
      return base64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
    }

    function cleanNulls(obj) {
      if (!obj || typeof obj !== 'object') return;

      if (Array.isArray(obj)) {
        obj.forEach(cleanNulls);
        return;
      }

      for (const key of Object.keys(obj)) {
        const val = obj[key];
        if (val === null || val === undefined) {
          delete obj[key];
        } else if (typeof val === 'object') {
          cleanNulls(val);
        }
      }
    }

    // Prepare creation options from server (decode binary fields to ArrayBuffers)
    function prepareCreationOptionsFromServer(pubKey) {
      const opts = structuredClone(pubKey);

      opts.challenge = base64urlToUint8Array(opts.challenge);
      opts.user.id   = base64urlToUint8Array(opts.user.id);

      if (Array.isArray(opts.excludeCredentials)) {
        opts.excludeCredentials = opts.excludeCredentials.map(cred => ({
          ...cred,
          id: base64urlToUint8Array(cred.id)
        }));
      }

      // Remove null/undefined fields that WebAuthn doesn't like
      cleanNulls(opts);

      return opts;
    }

    // Prepare request options from server (decode binary fields to ArrayBuffers)
    function prepareRequestOptionsFromServer(pubKey) {
      const opts = structuredClone(pubKey);

      opts.challenge = base64urlToUint8Array(opts.challenge);

      if (Array.isArray(opts.allowCredentials)) {
        opts.allowCredentials = opts.allowCredentials.map(cred => ({
          ...cred,
          id: base64urlToUint8Array(cred.id)
        }));
      }
      // Remove null/undefined fields that WebAuthn doesn't like
      cleanNulls(opts);

      return opts;
    }

    // Convert a WebAuthn credential (registration) into JSON for the server
    function registrationCredentialToJSON(cred) {
      return {
        id: cred.id,
        type: cred.type,
        rawId: arrayBufferToBase64url(cred.rawId),
        response: {
          clientDataJSON: arrayBufferToBase64url(cred.response.clientDataJSON),
          attestationObject: arrayBufferToBase64url(cred.response.attestationObject),
        },
        clientExtensionResults: cred.getClientExtensionResults(),
        authenticatorAttachment: cred.authenticatorAttachment
      };
    }

    // Convert a WebAuthn credential (authentication) into JSON for the server
    function assertionCredentialToJSON(cred) {
      return {
        id: cred.id,
        type: cred.type,
        rawId: arrayBufferToBase64url(cred.rawId),
        response: {
          clientDataJSON: arrayBufferToBase64url(cred.response.clientDataJSON),
          authenticatorData: arrayBufferToBase64url(cred.response.authenticatorData),
          signature: arrayBufferToBase64url(cred.response.signature),
          userHandle: cred.response.userHandle
            ? arrayBufferToBase64url(cred.response.userHandle)
            : null
        },
        clientExtensionResults: cred.getClientExtensionResults(),
        authenticatorAttachment: cred.authenticatorAttachment
      };
    }

// --- Public authentication forms -------------------------------------------

const authFormState = {busy: false, completed: false, redirectTimer: null};

function authElements(registration) {
  return {
    form: document.getElementById(registration ? 'registerForm' : 'loginForm'),
    input: document.getElementById(registration ? 'newname' : 'username'),
    button: document.getElementById(registration ? 'registerButton' : 'loginButton')
  };
}

function setAuthBusy(elements, busy, registration) {
  elements.form.setAttribute('aria-busy', String(busy));
  elements.input.disabled = busy;
  elements.button.disabled = busy;
  elements.button.textContent = busy
    ? registration ? 'Creating your passkey…' : 'Signing in…'
    : registration ? 'Create account with passkey' : 'Sign in with passkey';
}

async function authServerMessage(response, registration, verification) {
  let detail = '';
  try { detail = await response.text(); } catch (_) { /* Use the safe fallback below. */ }
  if (response.status === 400 && !verification) {
    if (registration && /already exists/i.test(detail)) {
      return 'This username is already registered. Sign in, or ask an administrator for help if registration was interrupted.';
    }
    if (!registration && /not found/i.test(detail)) {
      return 'We could not find that username. Check it and try again.';
    }
    if (/Invalid username/i.test(detail)) return 'Use only letters, numbers and underscores in your username.';
  }
  if (verification) {
    return registration
      ? 'Your passkey could not be saved. Ask an administrator for help if this username is now unavailable.'
      : 'Your passkey could not be verified. Please try signing in again.';
  }
  return 'The server could not start this request. Please try again shortly.';
}

function authCancelledMessage(registration) {
  return registration
    ? 'Passkey creation was cancelled or timed out. If this username is now unavailable, ask an administrator for help.'
    : 'Sign-in was cancelled or timed out. You can try again when you are ready.';
}

async function runPasskeyFlow(registration) {
  const elements = authElements(registration);
  if (!elements.form || !elements.input || !elements.button || authFormState.busy || authFormState.completed) return;
  const username = elements.input.value.trim();
  if (!username || !/^[A-Za-z0-9_]+$/.test(username) || username.length > 255) {
    elements.input.setAttribute('aria-invalid', 'true');
    log(!username ? 'Enter your username to continue.' : 'Use only letters, numbers and underscores, up to 255 characters.', 'error');
    elements.input.focus();
    return;
  }
  elements.input.removeAttribute('aria-invalid');
  if (window.isSecureContext === false) {
    log('Open the secure server address to use your passkey.', 'error');
    return;
  }
  const method = registration ? 'create' : 'get';
  if (!window.PublicKeyCredential || !navigator.credentials || typeof navigator.credentials[method] !== 'function') {
    log('This browser does not support passkeys. Try an up-to-date browser on your phone or computer.', 'error');
    return;
  }

  authFormState.busy = true;
  setAuthBusy(elements, true, registration);
  const endpoint = registration ? 'register' : 'authenticate';
  try {
    log(registration ? 'Preparing your passkey…' : 'Preparing sign-in…');
    const optionsResponse = await fetch(`/webauthn/${endpoint}/options?username=${encodeURIComponent(username)}`, {
      method: 'POST', redirect: 'error'
    });
    if (!optionsResponse.ok) {
      log(await authServerMessage(optionsResponse, registration, false), 'error');
      return;
    }
    const {requestId, publicKey} = await optionsResponse.json();
    const options = registration ? prepareCreationOptionsFromServer(publicKey) : prepareRequestOptionsFromServer(publicKey);
    log(registration ? 'Follow your browser’s instructions to create a passkey.' : 'Confirm with your passkey to continue.');
    const credential = await navigator.credentials[method]({publicKey: options});
    if (!credential) {
      log(authCancelledMessage(registration));
      return;
    }
    const credentialJSON = registration ? registrationCredentialToJSON(credential) : assertionCredentialToJSON(credential);
    log(registration ? 'Saving your passkey…' : 'Completing sign-in…');
    const verifyResponse = await fetch(`/webauthn/${endpoint}/verify?requestId=${encodeURIComponent(requestId)}`, {
      method: 'POST', redirect: 'error', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(credentialJSON)
    });
    if (verifyResponse.status !== 204) {
      log(await authServerMessage(verifyResponse, registration, true), 'error');
      return;
    }
    authFormState.completed = true;
    elements.form.setAttribute('aria-busy', 'false');
    elements.button.textContent = registration ? 'Account created' : 'Signed in';
    if (registration) {
      log('Your passkey is ready. Sign in next, then ask an administrator to activate your account. Returning to sign-in…', 'success');
      authFormState.redirectTimer = window.setTimeout(() => { window.location.href = '/'; }, 3000);
    } else {
      log('Signed in. Opening your dashboard…', 'success');
      window.location.href = '/private/overview';
    }
  } catch (error) {
    if (error?.name === 'NotAllowedError' || error?.name === 'AbortError') {
      log(authCancelledMessage(registration));
    } else if (error?.name === 'InvalidStateError' && registration) {
      log('A passkey for this account may already exist. Try signing in, or ask an administrator for help.', 'error');
    } else if (error?.name === 'SecurityError') {
      log('A passkey could not be used on this address. Open the usual secure server link and try again.', 'error');
    } else {
      log('We could not complete the request. Check your connection and try again.', 'error');
    }
  } finally {
    authFormState.busy = false;
    if (!authFormState.completed) setAuthBusy(elements, false, registration);
  }
}

async function registerPasskey() { return runPasskeyFlow(true); }
async function loginWithPasskey() { return runPasskeyFlow(false); }

function initializeAuthForms() {
  for (const registration of [false, true]) {
    const elements = authElements(registration);
    if (!elements.form || !elements.input || !elements.button) continue;
    elements.form.addEventListener('submit', event => {
      event.preventDefault();
      return runPasskeyFlow(registration);
    });
    elements.input.addEventListener('input', () => {
      elements.input.removeAttribute('aria-invalid');
      if (!authFormState.busy && !authFormState.completed) log('');
    });
    window.addEventListener('pageshow', event => {
      if (event.persisted) {
        if (authFormState.redirectTimer !== null) window.clearTimeout(authFormState.redirectTimer);
        authFormState.redirectTimer = null;
        authFormState.busy = false;
        authFormState.completed = false;
        setAuthBusy(elements, false, registration);
        log('');
      }
      if (!registration && document.activeElement === document.body && !elements.input.disabled) elements.input.focus();
    });
  }
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', initializeAuthForms, {once: true});
} else {
  initializeAuthForms();
}
