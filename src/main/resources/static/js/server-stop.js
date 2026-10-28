(function () {
  const recoveryTimeoutMs = 5 * 60 * 1000;
  const probeTimeoutMs = 5000;
  const probeIntervalMs = 1000;

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

  document.addEventListener('DOMContentLoaded', function () {
    const stopButton = document.getElementById('serverStopButton');
    const dialog = document.getElementById('serverStopDialog');
    const confirmButton = document.getElementById('serverStopConfirmButton');
    const cancelButton = document.getElementById('serverStopCancelButton');
    if (!stopButton || !dialog || !confirmButton || !cancelButton) return;

    let phase = 'idle';

    async function waitForServerRestart() {
      if (phase !== 'verifying' && phase !== 'timedOut') return;

      phase = 'waiting';
      confirmButton.disabled = true;
      confirmButton.textContent = 'Restarting...';
      cancelButton.disabled = false;
      cancelButton.textContent = 'Close';
      setStatus('Waiting for server to restart. This page will reload automatically.', false);

      const deadline = Date.now() + recoveryTimeoutMs;
      while (Date.now() < deadline) {
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), Math.min(probeTimeoutMs, deadline - Date.now()));
        let ready = false;
        try {
          const response = await fetch('/health', {
            cache: 'no-store',
            redirect: 'error',
            signal: controller.signal
          });
          ready = response.status === 200 && await response.text() === 'ok';
        } catch (_) {
          // Connection failures and timeouts are expected while the server restarts.
        } finally {
          clearTimeout(timeout);
        }

        if (ready && Date.now() < deadline) {
          phase = 'recovered';
          setStatus('Server is ready. Reloading...', false);
          window.location.reload();
          return;
        }

        const remaining = deadline - Date.now();
        if (remaining > 0) {
          await new Promise(resolve => setTimeout(resolve, Math.min(probeIntervalMs, remaining)));
        }
      }

      phase = 'timedOut';
      confirmButton.disabled = false;
      confirmButton.textContent = 'Retry connection';
      setStatus('The server has not restarted yet. Retry the connection check or close this dialog.', true);
    }

    async function requestServerStop() {
      if (phase !== 'idle') return;

      if (!window.PublicKeyCredential || !navigator.credentials) {
        setStatus('This browser does not support passkeys.', true);
        return;
      }

      phase = 'verifying';
      confirmButton.disabled = true;
      cancelButton.disabled = true;
      setStatus('Confirm with your passkey to stop the server.', false);

      try {
        const optionsResponse = await fetch('/admin/server-stop/options', {
          method: 'POST',
          redirect: 'error'
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
          redirect: 'error',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(assertionCredentialToJSON(assertion))
        });
        if (verifyResponse.status !== 202) {
          throw await responseError(verifyResponse, 'Server refused the stop request');
        }
      } catch (error) {
        phase = 'idle';
        const cancelled = error && (error.name === 'NotAllowedError' || error.name === 'AbortError');
        setStatus(cancelled ? 'Passkey confirmation was cancelled.' : error.message, true);
        confirmButton.disabled = false;
        cancelButton.disabled = false;
        return;
      }

      await waitForServerRestart();
    }

    function confirmAction() {
      return phase === 'timedOut' ? waitForServerRestart() : requestServerStop();
    }

    stopButton.addEventListener('click', function () {
      if (phase === 'idle') {
        confirmButton.disabled = false;
        cancelButton.disabled = false;
        confirmButton.textContent = 'Proceed';
        cancelButton.textContent = 'Cancel';
        setStatus('', false);
      }

      if (typeof dialog.showModal === 'function') {
        if (!dialog.open) dialog.showModal();
      } else if ((phase === 'idle' || phase === 'timedOut') && window.confirm(
        phase === 'timedOut' ? 'Check whether the server has restarted?' : 'Stop the server?'
      )) {
        confirmAction();
      }
    });

    confirmButton.addEventListener('click', confirmAction);
  });
}());
