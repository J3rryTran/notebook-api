// frontend/public/app/render.js
// Các hàm render giao diện kết quả: bảng SQL, text stream, lỗi, thanh trạng thái

function escapeHtml(str) {
  if (str === null || str === undefined) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function formatDuration(ms) {
  if (!ms || ms < 0) return '0 s';
  if (ms < 1000) return (ms / 1000).toFixed(2) + ' s';
  return (ms / 1000).toFixed(2) + ' s';
}

function formatCurrentTime() {
  const now = new Date();
  const hh = String(now.getHours()).padStart(2, '0');
  const mm = String(now.getMinutes()).padStart(2, '0');
  return `${hh}:${mm}`;
}

window.cellTableData = window.cellTableData || {};

/**
 * Render bảng kết quả từ dữ liệu Table JSON (application/vnd.notebook.table+json hoặc mảng rows)
 */
function renderTableOutput(tableData, stats = {}, cellId = '', executionId = '') {
  let columns = [];
  let rows = [];
  let truncated = false;
  let totalRows = 0;
  let offset = 0;
  let limit = 200;

  if (tableData) {
    if (tableData.columns && Array.isArray(tableData.columns)) {
      columns = tableData.columns.map(c => {
        if (typeof c === 'string') return { name: c, type: '' };
        return { name: c.name || c.columnName || 'col', type: c.type || '' };
      });
    }
    if (tableData.rows && Array.isArray(tableData.rows)) {
      rows = tableData.rows;
      totalRows = rows.length;
    }
    if (tableData.totalRows !== undefined && tableData.totalRows !== null) {
      totalRows = tableData.totalRows;
    } else if (tableData.rowCount !== undefined && tableData.rowCount !== null) {
      totalRows = tableData.rowCount;
    }
    if (tableData.offset !== undefined) offset = tableData.offset;
    if (tableData.limit !== undefined) limit = tableData.limit;
    truncated = !!tableData.truncated;
  }

  // Nếu không có cột nhưng có dòng là object
  if (columns.length === 0 && rows.length > 0 && typeof rows[0] === 'object' && !Array.isArray(rows[0])) {
    columns = Object.keys(rows[0]).map(k => ({ name: k, type: '' }));
  }

  const effectiveCellId = cellId || ('tbl-' + Math.random().toString(36).substring(2, 9));
  if (effectiveCellId) {
    window.cellTableData[effectiveCellId] = {
      executionId: executionId || '',
      columns: columns,
      rows: [...rows],
      totalRows: totalRows,
      offset: offset,
      limit: limit,
      truncated: truncated,
      stats: stats
    };
  }

  if (columns.length === 0 && rows.length === 0) {
    let emptyHtml = '<div class="table-output-container">';
    emptyHtml += '<div class="output-empty-msg">Truy vấn thực thi thành công (0 dòng kết quả).</div>';
    emptyHtml += '</div>';
    return emptyHtml;
  }

  let html = '<div class="table-output-container">';
  html += '<div class="table-scroll">';
  html += '<table class="result-table">';
  html += '<thead><tr>';
  html += '<th class="row-num-header">#</th>';
  for (const col of columns) {
    const colTitle = col.type ? `${escapeHtml(col.name)} (${escapeHtml(col.type)})` : escapeHtml(col.name);
    html += `<th title="${colTitle}">${escapeHtml(col.name)}${col.type ? ` <span class="col-type">${escapeHtml(col.type)}</span>` : ''}</th>`;
  }
  html += '</tr></thead>';

  html += `<tbody id="tbody-${effectiveCellId}">`;
  for (let i = 0; i < rows.length; i++) {
    const row = rows[i];
    html += `<tr><td class="row-num">${i + 1}</td>`;
    if (Array.isArray(row)) {
      for (let j = 0; j < columns.length; j++) {
        html += renderTableCell(row[j]);
      }
    } else if (typeof row === 'object' && row !== null) {
      for (let j = 0; j < columns.length; j++) {
        html += renderTableCell(row[columns[j].name]);
      }
    } else {
      html += renderTableCell(row);
    }
    html += '</tr>';
  }
  html += '</tbody></table></div>';

  const canLoadMore = executionId && rows.length < totalRows;
  const durationMs = stats.durationMs || stats.executionTimeMs;
  const durationStr = durationMs ? formatDuration(durationMs) : '';

  html += '<div class="table-footer">';
  html += '<div class="table-footer-left">';
  html += `<span id="table-count-label-${effectiveCellId}">Hiển thị ${rows.length}/${totalRows} dòng</span>`;
  if (canLoadMore) {
    html += `<button class="btn btn-secondary btn-sm btn-load-more" id="btn-load-more-${effectiveCellId}" onclick="loadMoreTableRows('${effectiveCellId}')"><i class="fa-solid fa-angles-down"></i> Tải thêm</button>`;
  }
  if (truncated) {
    html += '<span class="badge-truncated" title="Kết quả đã bị cắt bớt do vượt quá giới hạn engine"><i class="fa-solid fa-triangle-exclamation"></i> Kết quả đã bị cắt bớt</span>';
  }
  html += '</div>';

  html += '<div class="table-footer-right">';
  html += `<span>${totalRows} dòng</span>`;
  if (durationStr) {
    html += ` · <span>${durationStr}</span>`;
  }
  if (executionId) {
    html += ` · <button class="btn-link" onclick="viewExecutionLogs('${executionId}')"><i class="fa-solid fa-list-check"></i> Xem log</button>`;
  }
  html += ` · <button class="btn-link" onclick="downloadTableAsCsv('${effectiveCellId}')"><i class="fa-solid fa-file-csv"></i> Tải CSV</button>`;
  html += '</div>';
  html += '</div>';
  html += '</div>';

  return html;
}

function renderTableCell(val) {
  if (val === null || val === undefined) {
    return '<td class="cell-null">NULL</td>';
  }
  if (typeof val === 'number') {
    return `<td class="cell-number">${val}</td>`;
  }
  if (typeof val === 'boolean') {
    return `<td class="cell-bool">${val}</td>`;
  }
  if (typeof val === 'object') {
    const jsonStr = JSON.stringify(val);
    const escaped = escapeHtml(jsonStr);
    return `<td class="cell-json" title="${escaped}"><span class="cell-text-truncated">${escaped}</span></td>`;
  }
  const str = String(val);
  const escaped = escapeHtml(str);
  if (str.length > 50) {
    return `<td class="cell-string" title="${escaped}"><span class="cell-text-truncated">${escaped}</span></td>`;
  }
  return `<td>${escaped}</td>`;
}

function downloadTableAsCsv(cellId) {
  const tableData = window.cellTableData && window.cellTableData[cellId];
  if (!tableData || !tableData.rows || tableData.rows.length === 0) {
    alert('Không có dữ liệu để xuất CSV');
    return;
  }
  const cols = tableData.columns || [];
  const colNames = cols.map(c => typeof c === 'string' ? c : (c.name || 'col'));

  const escapeCsv = (val) => {
    if (val === null || val === undefined) return '';
    const str = String(val);
    if (str.includes(',') || str.includes('"') || str.includes('\n') || str.includes('\r')) {
      return '"' + str.replace(/"/g, '""') + '"';
    }
    return str;
  };

  const lines = [];
  lines.push(colNames.map(escapeCsv).join(','));

  for (const row of tableData.rows) {
    if (Array.isArray(row)) {
      lines.push(row.map(escapeCsv).join(','));
    } else if (typeof row === 'object' && row !== null) {
      lines.push(colNames.map(c => escapeCsv(row[c.name || c])).join(','));
    }
  }

  const csvContent = '\uFEFF' + lines.join('\r\n');
  const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `query_result_${cellId}_${Date.now()}.csv`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}
window.downloadTableAsCsv = downloadTableAsCsv;

async function loadMoreTableRows(cellId) {
  const tableState = window.cellTableData && window.cellTableData[cellId];
  if (!tableState || !tableState.executionId) return;

  const btn = document.getElementById(`btn-load-more-${cellId}`);
  if (btn) {
    btn.disabled = true;
    btn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> Đang tải...';
  }

  const currentCount = tableState.rows.length;
  const limit = tableState.limit || 200;

  try {
    const res = await api(`/api/v1/executions/${tableState.executionId}/results?offset=${currentCount}&limit=${limit}`);
    if (!res.ok) {
      alert('Không thể tải thêm kết quả: HTTP ' + res.status);
      if (btn) {
        btn.disabled = false;
        btn.innerHTML = '<i class="fa-solid fa-angles-down"></i> Tải thêm';
      }
      return;
    }
    const data = await res.json();
    let newRows = [];
    if (data.data && data.data['application/vnd.notebook.table+json']) {
      newRows = data.data['application/vnd.notebook.table+json'].rows || [];
    } else if (data.rows) {
      newRows = data.rows;
    }

    if (newRows.length > 0) {
      const tbody = document.getElementById(`tbody-${cellId}`);
      let html = '';
      for (let i = 0; i < newRows.length; i++) {
        const row = newRows[i];
        const rowIdx = currentCount + i + 1;
        html += `<tr><td class="row-num">${rowIdx}</td>`;
        if (Array.isArray(row)) {
          for (let j = 0; j < tableState.columns.length; j++) {
            html += renderTableCell(row[j]);
          }
        } else if (typeof row === 'object' && row !== null) {
          for (let j = 0; j < tableState.columns.length; j++) {
            const colName = typeof tableState.columns[j] === 'string' ? tableState.columns[j] : tableState.columns[j].name;
            html += renderTableCell(row[colName]);
          }
        } else {
          html += renderTableCell(row);
        }
        html += '</tr>';
      }
      if (tbody) {
        tbody.insertAdjacentHTML('beforeend', html);
      }
      tableState.rows.push(...newRows);
    }

    const countLabel = document.getElementById(`table-count-label-${cellId}`);
    if (countLabel) {
      countLabel.innerText = `Hiển thị ${tableState.rows.length}/${tableState.totalRows} dòng`;
    }

    if (btn) {
      if (tableState.rows.length >= tableState.totalRows || newRows.length === 0) {
        btn.style.display = 'none';
      } else {
        btn.disabled = false;
        btn.innerHTML = '<i class="fa-solid fa-angles-down"></i> Tải thêm';
      }
    }
  } catch (err) {
    alert('Lỗi tải thêm kết quả: ' + err.message);
    if (btn) {
      btn.disabled = false;
      btn.innerHTML = '<i class="fa-solid fa-angles-down"></i> Tải thêm';
    }
  }
}
window.loadMoreTableRows = loadMoreTableRows;

/**
 * Render văn bản stream (stdout của PySpark hoặc thông báo lệnh)
 */
function renderStreamOutput(text, stats = {}) {
  let html = '<div class="stream-output-container">';
  if (stats.durationMs) {
    html += `<div class="output-meta"><span class="meta-item"><i class="fa-regular fa-clock"></i> ${formatDuration(stats.durationMs)}</span></div>`;
  }
  html += `<pre class="stream-text">${escapeHtml(text)}</pre>`;
  html += '</div>';
  return html;
}

/**
 * Render lỗi thực thi
 */
function renderErrorOutput(errorInfo, executionId) {
  const ename = errorInfo.ename || 'ExecutionError';
  const evalue = errorInfo.evalue || errorInfo.message || 'Có lỗi xảy ra khi thực thi trên Spark engine.';
  const traceback = errorInfo.traceback || [];

  let html = '<div class="error-output-card">';
  html += '<div class="error-header">';
  html += `<i class="fa-solid fa-circle-exclamation error-icon"></i>`;
  html += `<span class="error-title">${escapeHtml(ename)}: ${escapeHtml(evalue)}</span>`;
  html += '</div>';

  html += '<div class="error-actions">';
  if (traceback.length > 0) {
    html += `<button class="btn-link" onclick="toggleTraceback('${executionId}')"><i class="fa-solid fa-chevron-down"></i> Chi tiết lỗi</button>`;
  }
  html += `<button class="btn-link" onclick="viewExecutionLogs('${executionId}')"><i class="fa-solid fa-list-check"></i> Xem logs Kyuubi</button>`;
  html += '</div>';

  if (traceback.length > 0) {
    html += `<div class="error-traceback" id="tb-${executionId}" style="display:none;">`;
    html += `<pre>${escapeHtml(traceback.join('\n'))}</pre>`;
    html += '</div>';
  }

  html += '</div>';
  return html;
}

function toggleTraceback(id) {
  const el = document.getElementById(`tb-${id}`);
  if (el) {
    el.style.display = el.style.display === 'none' ? 'block' : 'none';
  }
}

async function viewExecutionLogs(executionId) {
  try {
    const res = await api(`/api/v1/executions/${executionId}/logs?afterSequence=0`);
    if (res.ok) {
      const data = await res.json();
      const logs = data.logs || 'Không có bản ghi log nào.';
      showLogModal(`Kyuubi Logs - Execution ${executionId}`, logs);
    } else {
      alert('Không thể tải log của lần chạy này.');
    }
  } catch (e) {
    alert('Lỗi kết nối khi tải log: ' + e.message);
  }
}

function showLogModal(title, content) {
  let modal = document.getElementById('logViewerModal');
  if (!modal) {
    modal = document.createElement('div');
    modal.id = 'logViewerModal';
    modal.className = 'modal-backdrop';
    modal.innerHTML = `
      <div class="modal-box log-modal-box">
        <div class="modal-header">
          <h3 id="logModalTitle"></h3>
          <button class="modal-close" onclick="closeLogModal()">&times;</button>
        </div>
        <div class="modal-body">
          <pre id="logModalContent"></pre>
        </div>
        <div class="modal-footer">
          <button class="btn" onclick="closeLogModal()">Đóng</button>
        </div>
      </div>
    `;
    document.body.appendChild(modal);
  }

  document.getElementById('logModalTitle').innerText = title;
  document.getElementById('logModalContent').innerText = content;
  modal.style.display = 'flex';
}

function closeLogModal() {
  const modal = document.getElementById('logViewerModal');
  if (modal) modal.style.display = 'none';
}
