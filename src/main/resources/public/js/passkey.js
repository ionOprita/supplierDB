    function log(msg) {
      const div = document.getElementById('log');
      div.textContent = msg;
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

    // --- Registration flow -----------------------------------------------------

    async function registerPasskey() {
      const username = document.getElementById('username').value.trim();
      if (!username) {
        log('Please enter a username to register.');
        return;
      }
      try {
        log('Requesting registration options…');

        const resp = await fetch(`/webauthn/register/options?username=${encodeURIComponent(username)}`, {
          method: 'POST'
        });
        if (!resp.ok) {
          log(`Error from server: ${resp.status} ${resp.statusText}`);
          return;
        }
        const { requestId, publicKey } = await resp.json();

        const creationOpts = prepareCreationOptionsFromServer(publicKey);

        log('Creating credential with authenticator…');
        const cred = await navigator.credentials.create({ publicKey: creationOpts });

        if (!cred) {
          log('No credential created (user cancelled?).');
          return;
        }

        const credJSON = registrationCredentialToJSON(cred);

        log('Sending credential to server for verification…');

        const verifyResp = await fetch(`/webauthn/register/verify?requestId=${encodeURIComponent(requestId)}`, {
          method: 'POST',
          headers: {'Content-Type': 'application/json'},
          body: JSON.stringify(credJSON)
        });

        if (verifyResp.ok) {
          log('Passkey registered successfully ✔ You can now sign in with it.');
        } else {
          log(`Registration verification failed: ${verifyResp.status}`);
        }

      } catch (e) {
        console.error(e);
        log('Error during registration: ' + e);
      }
    }

    // --- Authentication flow ---------------------------------------------------

    async function loginWithPasskey() {
      const username = document.getElementById('username').value.trim();
      if (!username) {
        log('Please enter username for this simple example.');
        return;
      }
      try {
        log('Requesting authentication options…');

        const resp = await fetch(`/webauthn/authenticate/options?username=${encodeURIComponent(username)}`, {
          method: 'POST'
        });
        if (!resp.ok) {
          log(`Error from server: ${resp.status}`);
          return;
        }
        const { requestId, publicKey } = await resp.json();

        const requestOpts = prepareRequestOptionsFromServer(publicKey);

        log('Asking authenticator to sign assertion…');

        const assertion = await navigator.credentials.get({ publicKey: requestOpts });

        if (!assertion) {
          log('No assertion (user cancelled?).');
          return;
        }

        const assertionJSON = assertionCredentialToJSON(assertion);

        log('Sending assertion to server for verification…');

        const verifyResp = await fetch(`/webauthn/authenticate/verify?requestId=${encodeURIComponent(requestId)}`, {
          method: 'POST',
          headers: {'Content-Type': 'application/json'},
          body: JSON.stringify(assertionJSON)
        });

        if (verifyResp.ok) {
          log('Signed in ✔ You should now have a session on the server.');
          // Optionally redirect:
          // window.location.href = '/app/';
        } else {
          log(`Authentication verification failed: ${verifyResp.status}`);
        }
      } catch (e) {
        console.error(e);
        log('Error during authentication: ' + e);
      }
    }