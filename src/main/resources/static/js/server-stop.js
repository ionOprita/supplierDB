(function () {
  function setStatus(message, isError) {
    const status = document.getElementById('serverStopStatus');
    if (!status) return;

    status.textContent = message;
    status.classList.toggle('is-error', Boolean(isError));
  }

  function responseError(response, fallback) {
    return response.text()
      .then(text => {
        const detail = text ? `: ${text}` : '';
        return new Error(`${fallback} (${response.status})${detail}`);
      });
  }

  async function requestServerStop(confirmButton, cancelButton) {
    if (!window.PublicKeyCredential || !navigator.credentials) {
      setStatus('This browser does not support passkeys.', true);
      return;
    }

    confirmButton.disabled = true;
    cancelButton.disabled = true;
    setStatus('Confirm with your passkey to stop the server.', false);

    try {
      const optionsResponse = await fetch('/admin/server-stop/options', {
        method: 'POST'
      });
      if (!optionsResponse.ok) {
        throw await responseError(optionsResponse, 'Could not start passkey verification');
      }

      const { requestId, publicKey } = await optionsResponse.json();
      const requestOptions = prepareRequestOptionsFromServer(publicKey);
      const assertion = await navigator.credentials.get({ publicKey: requestOptions });
      if (!assertion) {
        throw new Error('No passkey response was returned.');
      }

      const verifyResponse = await fetch(`/admin/server-stop/verify?requestId=${encodeURIComponent(requestId)}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(assertionCredentialToJSON(assertion))
      });
      if (!verifyResponse.ok) {
        throw await responseError(verifyResponse, 'Server refused the stop request');
      }

      confirmButton.textContent = 'Stopping...';
      setStatus('Server stop requested. It should restart shortly.', false);
    } catch (error) {
      const cancelled = error && (error.name === 'NotAllowedError' || error.name === 'AbortError');
      setStatus(cancelled ? 'Passkey confirmation was cancelled.' : error.message, true);
      confirmButton.disabled = false;
      cancelButton.disabled = false;
    }
  }

  document.addEventListener('DOMContentLoaded', function () {
    const stopButton = document.getElementById('serverStopButton');
    const dialog = document.getElementById('serverStopDialog');
    const confirmButton = document.getElementById('serverStopConfirmButton');
    const cancelButton = document.getElementById('serverStopCancelButton');
    if (!stopButton || !dialog || !confirmButton || !cancelButton) return;

    stopButton.addEventListener('click', function () {
      confirmButton.disabled = false;
      cancelButton.disabled = false;
      confirmButton.textContent = 'Proceed';
      setStatus('', false);

      if (typeof dialog.showModal === 'function') {
        dialog.showModal();
      } else if (window.confirm('Stop the server?')) {
        requestServerStop(confirmButton, cancelButton);
      }
    });

    confirmButton.addEventListener('click', function () {
      requestServerStop(confirmButton, cancelButton);
    });
  });
}());
