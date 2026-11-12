async function fetchSnapshot() {
  const response = await fetch('/admin/thread-dump/snapshot', {
    credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'application/json' }
  });
  if (!response.ok || !response.headers.get('Content-Type')?.includes('application/json')) {
    throw new Error('Could not capture the thread dump. Check that you are still signed in as an administrator.');
  }
  return response.json();
}

export function formatThreadDump(snapshot, threads = snapshot.threads) {
  return [snapshot.header, ...threads.map(thread => thread.text)].join('\n\n') + '\n';
}

export function downloadThreadDump(text, capturedAt, documentRoot = document, urlApi = URL) {
  const url = urlApi.createObjectURL(new Blob([text], { type: 'text/plain;charset=utf-8' }));
  const link = documentRoot.createElement('a');
  link.href = url;
  link.download = `thread-dump-${capturedAt.replace(/[:.]/g, '-')}.txt`;
  try {
    documentRoot.body.appendChild(link);
    link.click();
  } finally {
    link.remove();
    setTimeout(() => urlApi.revokeObjectURL(url), 0);
  }
}

export function initThreadDump({ documentRoot = document, request = fetchSnapshot,
  clipboard = globalThis.navigator?.clipboard,
  save = (text, capturedAt) => downloadThreadDump(text, capturedAt, documentRoot) } = {}) {
  const byId = name => documentRoot.getElementById(`threadDump${name}`);
  const captureButton = byId('Capture');
  if (!captureButton) return null;
  const filter = byId('Filter');
  const copyButton = byId('Copy');
  const downloadButton = byId('Download');
  const status = byId('Status');
  const summary = byId('Summary');
  const content = byId('Content');
  let snapshot = null;
  let busy = false;

  function message(text, isError = false) {
    status.textContent = text;
    status.classList.toggle('error-message', isError);
  }

  function setBusy(value) {
    busy = value;
    captureButton.disabled = value;
    copyButton.disabled = downloadButton.disabled = value || !snapshot;
    filter.disabled = !snapshot;
    content.setAttribute('aria-busy', String(value));
  }

  function render() {
    if (!snapshot) return;
    const name = filter.value.trim().toLowerCase();
    const visible = snapshot.threads.filter(thread => thread.name.toLowerCase().includes(name));
    summary.textContent = `${visible.length} of ${snapshot.threads.length} platform threads shown. Captured at ${snapshot.capturedAt}.`
      + (snapshot.deadlockedThreadIds?.length ? ` ${snapshot.deadlockedThreadIds.length} deadlocked threads detected.` : '');
    content.textContent = formatThreadDump(snapshot, visible)
      + (visible.length ? '' : '\nNo threads match this name.\n');
  }

  async function capture() {
    if (busy) return;
    setBusy(true);
    message('Capturing thread dump…');
    try {
      const next = await request();
      if (typeof next?.capturedAt !== 'string' || typeof next.header !== 'string'
          || !Array.isArray(next.threads)
          || !next.threads.every(thread => typeof thread.name === 'string' && typeof thread.text === 'string')) {
        throw new Error('The server returned an invalid thread dump.');
      }
      snapshot = next;
      render();
      message(`Thread dump captured at ${snapshot.capturedAt}. Copy and download include all threads in this snapshot.`);
    } catch (error) {
      message((snapshot ? `Refresh failed. Showing the previous snapshot from ${snapshot.capturedAt}. ` : 'Capture failed. ')
        + (error.message || 'Please try again.'), true);
    } finally {
      setBusy(false);
    }
  }

  captureButton.addEventListener('click', capture);
  filter.addEventListener('input', render);
  copyButton.addEventListener('click', async () => {
    if (!snapshot || busy) return;
    if (!clipboard?.writeText) {
      message('Clipboard access is unavailable. Use Download full dump instead.', true);
      return;
    }
    const copiedSnapshot = snapshot;
    try {
      await clipboard.writeText(formatThreadDump(copiedSnapshot));
      message(`Copied the full thread dump captured at ${copiedSnapshot.capturedAt}.`);
    } catch {
      message('Could not copy the thread dump. Use Download full dump instead.', true);
    }
  });
  downloadButton.addEventListener('click', () => {
    if (!snapshot || busy) return;
    try {
      save(formatThreadDump(snapshot), snapshot.capturedAt);
      message(`Downloaded the full thread dump captured at ${snapshot.capturedAt}.`);
    } catch {
      message('Could not download the thread dump. Please try again.', true);
    }
  });

  const ready = capture();
  return { capture, ready };
}

if (typeof document !== 'undefined') initThreadDump();
