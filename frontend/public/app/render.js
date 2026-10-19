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

/**
 * Render bảng kết quả từ dữ liệu Table JSON (application/vnd.notebook.table+json hoặc mảng rows)
 */
function renderTableOutput(tableData, stats = {}) {
  let columns = [];
  let rows = [];
  let truncated = false;
  let totalRows = 0;

  if (tableData) {
    if (tableData.columns && Array.isArray(tableData.columns)) {
      columns = tableData.columns.map(c => (typeof c === 'string' ? c : c.name || c.columnName || 'col'));
    }
    if (tableData.rows && Array.isArray(tableData.rows)) {
      rows = tableData.rows;
      totalRows = rows.length;
    }
    if (tableData.rowCount !== undefined) {
      totalRows = tableData.rowCount;
    }
    truncated = !!tableData.truncated;
  }

  // Nếu không có cột nhưng có dòng là object
  if (columns.length === 0 && rows.length > 0 && typeof rows[0] === 'object' && !Array.isArray(rows[0])) {
    columns = Object.keys(rows[0]);
  }

  let html = '<div class="table-output-container">';

  // Stats bar
  html += '<div class="output-meta">';
  html += `<span class="meta-item"><i class="fa-solid fa-table"></i> ${totalRows} dòng</span>`;
  if (stats.durationMs) {
    html += `<span class="meta-item"><i class="fa-regular fa-clock"></i> ${formatDuration(stats.durationMs)}</span>`;
  }
  if (truncated) {
    html += '<span class="badge-truncated"><i class="fa-solid fa-triangle-exclamation"></i> Kết quả đã bị cắt bớt</span>';
  }
  html += '</div>';

  if (columns.length === 0 && rows.length === 0) {
    html += '<div class="output-empty-msg">Truy vấn thực thi thành công (0 dòng kết quả).</div>';
    html += '</div>';
    return html;
  }

  html += '<div class="table-scroll">';
  html += '<table class="result-table">';
  html += '<thead><tr>';
  html += '<th class="row-num-header">#</th>';
  for (const col of columns) {
    html += `<th>${escapeHtml(col)}</th>`;
  }
  html += '</tr></thead>';

  html += '<tbody>';
  for (let i = 0; i < rows.length; i++) {
    const row = rows[i];
    html += `<tr><td class="row-num">${i + 1}</td>`;
    if (Array.isArray(row)) {
      for (let j = 0; j < columns.length; j++) {
        const val = row[j];
        html += renderTableCell(val);
      }
    } else if (typeof row === 'object' && row !== null) {
      for (let j = 0; j < columns.length; j++) {
        const val = row[columns[j]];
        html += renderTableCell(val);
      }
    } else {
      html += renderTableCell(row);
    }
    html += '</tr>';
  }
  html += '</tbody></table></div>';
  html += '</div>';

  return html;
}

function renderTableCell(val) {
  if (val === null || val === undefined) {
    return '<td class="cell-null">null</td>';
  }
  if (typeof val === 'number') {
    return `<td class="cell-number">${val}</td>`;
  }
  if (typeof val === 'boolean') {
    return `<td class="cell-bool">${val}</td>`;
  }
  if (typeof val === 'object') {
    return `<td class="cell-json">${escapeHtml(JSON.stringify(val))}</td>`;
  }
  return `<td>${escapeHtml(String(val))}</td>`;
}

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
