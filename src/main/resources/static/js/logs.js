export function initLogTables(documentRoot = document) {
  const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' });

  for (const section of documentRoot.querySelectorAll('.log-files-section')) {
    const table = section.querySelector('.log-files-table');
    const filter = section.querySelector('.log-name-filter');
    if (!table || !filter) continue;

    const body = table.tBodies[0];
    const rows = Array.from(body.querySelectorAll('[data-log-file]')).map((row, index) => ({
      row,
      index,
      name: row.cells[0].textContent,
      values: [
        row.cells[0].textContent,
        Number(row.cells[1].dataset.sortValue),
        Number(row.cells[2].dataset.sortValue)
      ]
    }));
    const buttons = Array.from(table.querySelectorAll('.log-sort-button'));
    const noMatches = documentRoot.createElement('tr');
    const message = documentRoot.createElement('td');
    message.colSpan = 4;
    message.textContent = 'No matching log files.';
    noMatches.appendChild(message);
    let sortColumn = 2;
    let sortDirection = 'descending';

    function render() {
      // Preserve the server's empty-directory message when there are no files.
      if (!rows.length) return;
      const query = filter.value.toLowerCase();
      const visible = rows.filter(({ name }) => name.toLowerCase().includes(query));
      visible.sort((a, b) => {
        const comparison = sortColumn === 0
          ? collator.compare(a.values[0], b.values[0])
          : a.values[sortColumn] - b.values[sortColumn];
        return (sortDirection === 'descending' ? -comparison : comparison) || a.index - b.index;
      });
      body.replaceChildren(...(visible.length ? visible.map(({ row }) => row) : [noMatches]));
    }

    filter.addEventListener('input', render);
    for (const button of buttons) {
      button.addEventListener('click', () => {
        const column = Number(button.dataset.sortColumn);
        sortDirection = sortColumn === column && sortDirection === 'ascending'
          ? 'descending' : 'ascending';
        sortColumn = column;
        for (const candidate of buttons) candidate.parentElement.removeAttribute('aria-sort');
        button.parentElement.setAttribute('aria-sort', sortDirection);
        render();
      });
    }
    render();
  }
}

if (typeof document !== 'undefined') initLogTables();
