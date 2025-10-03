// Fetch & render /returnsTable into a matrix.
// JSON shape (example):
// {
//   "ProductInfo[pnk=XXXX, productCode=..., name=Some Name, ...]": {
//      "2023-09": 0, "2023-10": 2, ...
//   },
//   ...
// }

const HEAD = document.getElementById('returnsHead');
const BODY = document.getElementById('returnsBody');
const TABLE = document.getElementById('returnsTable');

// Keep a reference so we can reuse the same tab/window
let detailsWin = null;

// --- helpers -------------------------------------------------------------

// Extract pnk and name from the ProductInfo[...] key string
function parseKey(key) {
    // tolerant regexes
    const pnkMatch = key.match(/pnk=([^,\]]+)/);
    const nameMatch = key.match(/name=([^,\]]+)/);
    return {
        pnk: pnkMatch ? pnkMatch[1].trim() : '',
        name: nameMatch ? nameMatch[1].trim() : key // fallback: whole key
    };
}

// Get months from an object, in ascending YYYY-MM order.
// We build the union across all rows, then sort lexicographically
// (works because keys are YYYY-MM).
function collectAllMonths(rows) {
    const set = new Set();
    for (const row of rows) {
        for (const m of Object.keys(row.months)) set.add(m);
    }
    return [...set].sort(); // "2023-09" < "2023-10" < ...
}

// Keep the original row order as provided by the API
function toRows(jsonMap) {
    // Object.entries keeps insertion order in modern JS engines.
    return Object.entries(jsonMap).map(([key, monthsObj]) => {
        const {pnk, name} = parseKey(key);
        return {key, pnk, name, months: monthsObj};
    });
}

// --- rendering -----------------------------------------------------------

function renderHeader(months) {
    const tr = document.createElement('tr');

    const thName = document.createElement('th');
    thName.textContent = 'Name';
    tr.appendChild(thName);
    const thPnk = document.createElement('th');
    thPnk.textContent = 'PNK';
    tr.appendChild(thPnk);

    for (const m of months) {
        const th = document.createElement('th');
        th.textContent = m;         // "YYYY-MM"
        tr.appendChild(th);
    }
    HEAD.innerHTML = '';
    HEAD.appendChild(tr);
}

function renderBody(rows, months) {
    BODY.innerHTML = '';
    const frag = document.createDocumentFragment();

    for (const row of rows) {
        const tr = document.createElement('tr');

        const tdName = document.createElement('td');
        tdName.textContent = row.name;
        tr.appendChild(tdName);
        const tdPnk = document.createElement('td');
        tdPnk.textContent = row.pnk;
        tr.appendChild(tdPnk);

        for (const m of months) {
            const td = document.createElement('td');
            // Important: no sorting/filtering of rows; months are shared header order.
            const val = row.months[m] ?? '';
            td.textContent = Number.isFinite(val) ? String(val) : '';
            td.dataset.clickable = "true";
            td.dataset.pnk = row.pnk;
            td.dataset.month = m;
            tr.appendChild(td);
        }
        frag.appendChild(tr);
    }
    BODY.appendChild(frag);
    // NEW: after DOM is in place, compute sticky offsets
    applyStickyOffsets();
}

function applyStickyOffsets() {
    const table = document.getElementById('returnsTable');
    if (!table) return;

    // Header cells
    const ths = table.tHead?.rows?.[0]?.cells ?? [];
    if (ths.length >= 1) ths[0].classList.add('sticky-col-1');
    if (ths.length >= 2) ths[1].classList.add('sticky-col-2');

    // Body cells: mark col 1 & 2 sticky
    for (const row of table.tBodies[0].rows) {
        if (row.cells.length >= 1) row.cells[0].classList.add('sticky-col-1');
        if (row.cells.length >= 2) row.cells[1].classList.add('sticky-col-2');
    }

    // Measure the actual width of column 1 (use header if available, else first row)
    const firstColCell =
        (ths.length ? ths[0] : null) ||
        (table.tBodies[0].rows[0]?.cells[0] ?? null);

    if (!firstColCell) return;

    // Get exact rendered width including padding/border
    const firstColWidth = firstColCell.getBoundingClientRect().width;

    // Set the left offset for col 2 to the measured width
    const stickyCol2 = table.querySelectorAll('.sticky-col-2');
    for (const el of stickyCol2) {
        el.style.left = `${firstColWidth}px`;
    }
}

function openOrUpdateDetails(pnk, month) {
    const url = `/return-details.html?pnk=${encodeURIComponent(pnk)}&month=${encodeURIComponent(month)}`;

    // Open (or reuse) a named window "returnDetails"
    // If it already exists, this navigates it to the new URL.
    detailsWin = window.open(url, "returnDetails");

    // If a popup blocker blocked it, navigate the current page as a fallback
    if (!detailsWin) {
        window.location.href = url;
        return;
    }
    // Try to bring it to the front
    detailsWin.focus?.();
}

// Delegated click handler (future API call placeholder)
TABLE.addEventListener('click', (ev) => {
    const td = ev.target.closest('td[data-clickable="true"]');
    if (!td) return;
    const {pnk, month} = td.dataset;
    openOrUpdateDetails(pnk, month)
});

// --- bootstrap -----------------------------------------------------------

async function fetchJSON(url) {
    const res = await fetch(url, {headers: {'Accept': 'application/json'}});
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return res.json();
}

(async function init() {
    try {
        const data = await fetchJSON('/returnTable');
        const rows = toRows(data);               // keep the original row insertion order
        const months = collectAllMonths(rows);   // header columns (YYYY-MM, ascending)
        renderHeader(months);
        renderBody(rows, months);
    } catch (e) {
        HEAD.innerHTML = '';
        BODY.innerHTML = `<tr><td>Failed to load /returnsTable</td></tr>`;
        // Also log for debugging:
        console.error(e);
    }
})();

// Also recompute on window resize (and orientation changes)
window.addEventListener('resize', () => applyStickyOffsets());
