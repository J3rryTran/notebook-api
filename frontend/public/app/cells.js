// frontend/public/app/cells.js
// Quản lý và render các cell trong notebook: thêm, sửa, xoá, di chuyển, phím tắt và auto-save

let cellSaveTimers = {};

function renderCellsList(cells) {
  const container = document.getElementById('cellContainer');
  if (!container) return;

  if (!cells || cells.length === 0) {
    container.innerHTML = '<div class="empty-cells-msg">Notebook chưa có cell nào. Hãy thêm một cell để bắt đầu.</div>';
    return;
  }

  let html = '';
  for (let i = 0; i < cells.length; i++) {
    const cell = cells[i];
    html += renderCellHtml(cell, i);
  }

  container.innerHTML = html;

  // Auto-resize cho toàn bộ textareas sau khi render
  setTimeout(() => {
    document.querySelectorAll('.cell-textarea').forEach(autoResizeTextarea);
  }, 10);
}

function renderCellHtml(cell, index) {
  const lang = (cell.language || 'SQL').toUpperCase();
  const execCount = cell.executionCount !== undefined && cell.executionCount !== null
    ? `[${cell.executionCount}]`
    : '[ ]';

  return `
    <div class="cell-card" id="card-${cell.id}" data-id="${cell.id}" onclick="setActiveCell('${cell.id}')">
      <div class="cell-toolbar">
        <div class="cell-toolbar-left">
          <select class="cell-lang-select" onchange="onCellLanguageChanged('${cell.id}', this.value)">
            <option value="SQL" ${lang === 'SQL' ? 'selected' : ''}>SQL</option>
            <option value="PYTHON" ${lang === 'PYTHON' || lang === 'PYSPARK' ? 'selected' : ''}>PySpark</option>
            <option value="MARKDOWN" ${lang === 'MARKDOWN' ? 'selected' : ''}>Markdown</option>
          </select>
          <span class="cell-exec-count" id="count-${cell.id}">${execCount}</span>
          <span class="cell-status-msg" id="status-${cell.id}"></span>
        </div>
        <div class="cell-actions">
          <button class="cell-btn run" onclick="runCell('${cell.id}', event)" title="Chạy cell (Shift+Enter)">
            <i class="fa-solid fa-play"></i>
          </button>
          <button class="cell-btn" onclick="moveCell('${cell.id}', -1, event)" title="Di chuyển lên">
            <i class="fa-solid fa-arrow-up"></i>
          </button>
          <button class="cell-btn" onclick="moveCell('${cell.id}', 1, event)" title="Di chuyển xuống">
            <i class="fa-solid fa-arrow-down"></i>
          </button>
          <button class="cell-btn delete" onclick="deleteCell('${cell.id}', event)" title="Xoá cell">
            <i class="fa-solid fa-trash-can"></i>
          </button>
        </div>
      </div>
      <div class="cell-editor">
        <textarea class="cell-textarea" id="editor-${cell.id}"
          placeholder="Nhập mã ${lang} tại đây..."
          oninput="onCellInput('${cell.id}', this)"
          onkeydown="onCellKeyDown('${cell.id}', event, this)">${escapeHtml(cell.source || '')}</textarea>
      </div>
      <div class="cell-output-area" id="output-${cell.id}">
        ${renderCellInitialOutput(cell)}
      </div>
    </div>
  `;
}

function renderCellInitialOutput(cell) {
  if (!cell.outputs) return '';
  let outData = cell.outputs;
  if (typeof outData === 'string' && outData.trim() !== '') {
    try {
      outData = JSON.parse(outData);
    } catch (e) {
      return renderStreamOutput(outData);
    }
  }
  if (Array.isArray(outData)) {
    if (outData.length === 0) return '';
    return outData.map(item => renderParsedOutput(item, cell.id, null)).join('');
  }
  if (typeof outData === 'object' && outData !== null) {
    return renderParsedOutput(outData, cell.id, null);
  }
  return '';
}

function renderParsedOutput(data, cellId, executionId = null, stats = {}) {
  if (!data) return '';
  if (Array.isArray(data)) {
    return data.map(item => renderParsedOutput(item, cellId, executionId, stats)).join('');
  }
  if (data.ename || data.output_type === 'error') {
    return renderErrorOutput(data, executionId || cellId);
  }
  if (data.data && data.data['application/vnd.notebook.table+json']) {
    return renderTableOutput(data.data['application/vnd.notebook.table+json'], stats, cellId, executionId);
  }
  if (data.rows || data.columns) {
    return renderTableOutput(data, stats, cellId, executionId);
  }
  if (data.text || data.output_type === 'stream') {
    return renderStreamOutput(data.text || (Array.isArray(data.traceback) ? data.traceback.join('\n') : ''), stats);
  }
  return renderStreamOutput(JSON.stringify(data, null, 2), stats);
}

async function saveCellOutputs(cellId, outputs, executionCount) {
  if (!currentNotebook) return;
  try {
    await api(`/api/v1/notebooks/${currentNotebook.id}/cells/${cellId}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        outputs: outputs,
        executionCount: executionCount
      })
    });
    if (currentNotebook.cells) {
      const c = currentNotebook.cells.find(cell => cell.id === cellId);
      if (c) {
        c.outputs = outputs;
        c.executionCount = executionCount;
      }
    }
  } catch (err) {
    console.warn('Lỗi lưu output cell vào document:', err);
  }
}
window.saveCellOutputs = saveCellOutputs;

function setActiveCell(cellId) {
  document.querySelectorAll('.cell-card').forEach(c => c.classList.remove('active'));
  const el = document.getElementById(`card-${cellId}`);
  if (el) el.classList.add('active');
}

function autoResizeTextarea(textarea) {
  if (!textarea) return;
  textarea.style.height = 'auto';
  textarea.style.height = Math.max(textarea.scrollHeight, 48) + 'px';
}

function onCellInput(cellId, textarea) {
  autoResizeTextarea(textarea);
  setSaveStatus('saving');

  if (cellSaveTimers[cellId]) {
    clearTimeout(cellSaveTimers[cellId]);
  }

  cellSaveTimers[cellId] = setTimeout(() => {
    saveCellSource(cellId);
  }, 1500);
}

function onCellKeyDown(cellId, event, textarea) {
  // Tab: chèn 4 dấu cách thay vì nhảy focus
  if (event.key === 'Tab') {
    event.preventDefault();
    const start = textarea.selectionStart;
    const end = textarea.selectionEnd;
    const value = textarea.value;
    textarea.value = value.substring(0, start) + '    ' + value.substring(end);
    textarea.selectionStart = textarea.selectionEnd = start + 4;
    onCellInput(cellId, textarea);
    return;
  }

  // Shift + Enter: Chạy cell hiện tại và chuyển sang cell tiếp theo
  if (event.key === 'Enter' && event.shiftKey) {
    event.preventDefault();
    saveCellSource(cellId, true);
    if (window.runCell) {
      window.runCell(cellId);
    }
    focusNextOrAddNewCell(cellId);
    return;
  }

  // Ctrl + Enter: Chạy cell hiện tại tại chỗ
  if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
    event.preventDefault();
    saveCellSource(cellId, true);
    if (window.runCell) {
      window.runCell(cellId);
    }
    return;
  }

  // Ctrl + S: Lưu ngay lập tức
  if ((event.key === 's' || event.key === 'S') && (event.ctrlKey || event.metaKey)) {
    event.preventDefault();
    saveCellSource(cellId, true);
  }
}

async function saveCellSource(cellId, forceImmediate = false) {
  if (cellSaveTimers[cellId]) {
    clearTimeout(cellSaveTimers[cellId]);
    delete cellSaveTimers[cellId];
  }

  const textarea = document.getElementById(`editor-${cellId}`);
  if (!textarea || !currentNotebook) return;

  const newSource = textarea.value;
  const cell = currentNotebook.cells.find(c => c.id === cellId);
  if (cell) {
    cell.source = newSource;
  }

  try {
    const res = await api(`/api/v1/notebooks/${currentNotebook.id}/cells/${cellId}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        cellType: cell ? cell.cellType : 'CODE',
        language: cell ? cell.language : 'SQL',
        source: newSource
      })
    });

    if (res.ok) {
      setSaveStatus('saved');
    } else {
      setSaveStatus('error');
    }
  } catch (e) {
    console.error('Lỗi khi lưu cell:', e);
    setSaveStatus('error');
  }
}

async function onCellLanguageChanged(cellId, newLang) {
  const cell = currentNotebook.cells.find(c => c.id === cellId);
  if (!cell) return;

  cell.language = newLang;
  const textarea = document.getElementById(`editor-${cellId}`);
  if (textarea) {
    textarea.placeholder = `Nhập mã ${newLang} tại đây...`;
  }

  try {
    setSaveStatus('saving');
    await api(`/api/v1/notebooks/${currentNotebook.id}/cells/${cellId}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        cellType: newLang === 'MARKDOWN' ? 'MARKDOWN' : 'CODE',
        language: newLang,
        source: cell.source || ''
      })
    });
    setSaveStatus('saved');
  } catch (e) {
    console.error('Lỗi đổi ngôn ngữ cell:', e);
    setSaveStatus('error');
  }
}

async function addCell(language = 'SQL', afterCellId = null) {
  if (!currentNotebook) return;

  let position = currentNotebook.cells.length;
  if (afterCellId) {
    const idx = currentNotebook.cells.findIndex(c => c.id === afterCellId);
    if (idx !== -1) {
      position = idx + 1;
    }
  }

  try {
    setSaveStatus('saving');
    const res = await api(`/api/v1/notebooks/${currentNotebook.id}/cells`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        cellType: language === 'MARKDOWN' ? 'MARKDOWN' : 'CODE',
        language: language,
        source: '',
        position: position
      })
    });

    if (res.ok) {
      const newCell = await res.json();
      currentNotebook.cells.splice(position, 0, newCell);
      renderCellsList(currentNotebook.cells);
      setSaveStatus('saved');

      setTimeout(() => {
        const nextEditor = document.getElementById(`editor-${newCell.id}`);
        if (nextEditor) {
          nextEditor.focus();
          setActiveCell(newCell.id);
        }
      }, 50);
    } else {
      alert('Không thể thêm cell mới.');
      setSaveStatus('error');
    }
  } catch (e) {
    console.error('Lỗi thêm cell:', e);
    setSaveStatus('error');
  }
}

async function deleteCell(cellId, event) {
  if (event) event.stopPropagation();
  if (!currentNotebook) return;

  if (currentNotebook.cells.length <= 1) {
    if (confirm('Đây là cell duy nhất. Xoá cell này sẽ tạo lại 1 cell SQL trống. Bạn có muốn tiếp tục?')) {
      await api(`/api/v1/notebooks/${currentNotebook.id}/cells/${cellId}`, { method: 'DELETE' });
      currentNotebook.cells = [];
      await createInitialCell(currentNotebook.id);
    }
    return;
  }

  try {
    setSaveStatus('saving');
    const res = await api(`/api/v1/notebooks/${currentNotebook.id}/cells/${cellId}`, {
      method: 'DELETE'
    });

    if (res.ok || res.status === 204) {
      currentNotebook.cells = currentNotebook.cells.filter(c => c.id !== cellId);
      const card = document.getElementById(`card-${cellId}`);
      if (card) card.remove();
      setSaveStatus('saved');
    } else {
      alert('Xoá cell thất bại.');
      setSaveStatus('error');
    }
  } catch (e) {
    alert('Lỗi khi xoá cell: ' + e.message);
    setSaveStatus('error');
  }
}

async function moveCell(cellId, direction, event) {
  if (event) event.stopPropagation();
  if (!currentNotebook) return;

  const idx = currentNotebook.cells.findIndex(c => c.id === cellId);
  if (idx === -1) return;

  const targetIdx = idx + direction;
  if (targetIdx < 0 || targetIdx >= currentNotebook.cells.length) return;

  // Đổi chỗ trong mảng
  const item = currentNotebook.cells.splice(idx, 1)[0];
  currentNotebook.cells.splice(targetIdx, 0, item);

  const cellIds = currentNotebook.cells.map(c => c.id);

  try {
    setSaveStatus('saving');
    const res = await api(`/api/v1/notebooks/${currentNotebook.id}/cells:reorder`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        cellIds: cellIds,
        version: currentNotebook.version
      })
    });

    if (res.ok || res.status === 204) {
      renderCellsList(currentNotebook.cells);
      setActiveCell(cellId);
      setSaveStatus('saved');
    } else {
      alert('Không thể đổi thứ tự cell.');
      setSaveStatus('error');
    }
  } catch (e) {
    console.error('Lỗi reorder cell:', e);
    setSaveStatus('error');
  }
}

function focusNextOrAddNewCell(currentCellId) {
  if (!currentNotebook) return;
  const idx = currentNotebook.cells.findIndex(c => c.id === currentCellId);
  if (idx !== -1 && idx < currentNotebook.cells.length - 1) {
    const nextCell = currentNotebook.cells[idx + 1];
    const nextEditor = document.getElementById(`editor-${nextCell.id}`);
    if (nextEditor) {
      nextEditor.focus();
      setActiveCell(nextCell.id);
    }
  } else {
    // Là cell cuối: tự động thêm 1 cell SQL mới
    addCell('SQL', currentCellId);
  }
}
