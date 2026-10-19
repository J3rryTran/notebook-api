// frontend/public/app/notebook.js
// Quản lý trạng thái Notebook: danh sách, tạo mới, mở, đổi tên, xoá, nhập/xuất, trạng thái lưu và Kyuubi status

let currentNotebook = null;
let notebookList = [];
let kyuubiStatusTimer = null;

async function loadNotebookList() {
  try {
    const res = await api('/api/v1/notebooks?limit=100');
    if (res.ok) {
      const data = await res.json();
      notebookList = data.items || [];
      renderNotebookDropdownMenu();
      return notebookList;
    }
  } catch (e) {
    console.warn('Lỗi tải danh sách notebook:', e);
  }
  return [];
}

function renderNotebookDropdownMenu(filterText = '') {
  const container = document.getElementById('nbDropdownList');
  if (!container) return;

  const filtered = notebookList.filter(nb => {
    return !filterText || (nb.name && nb.name.toLowerCase().includes(filterText.toLowerCase()));
  });

  if (filtered.length === 0) {
    container.innerHTML = '<div class="dropdown-empty-item">Không tìm thấy notebook nào</div>';
    return;
  }

  let html = '';
  for (const nb of filtered) {
    const isActive = currentNotebook && currentNotebook.id === nb.id;
    html += `
      <div class="dropdown-item ${isActive ? 'active' : ''}" onclick="openNotebook('${nb.id}')">
        <div class="dropdown-item-info">
          <i class="fa-regular fa-file-code dropdown-item-icon"></i>
          <span class="dropdown-item-name" title="${escapeHtml(nb.name)}">${escapeHtml(nb.name)}</span>
        </div>
        <button class="btn-icon-subtle" onclick="deleteNotebookFromList('${nb.id}', event)" title="Xoá notebook">
          <i class="fa-solid fa-trash-can"></i>
        </button>
      </div>
    `;
  }
  container.innerHTML = html;
}

function toggleNotebookDropdown(forceClose = false) {
  const menu = document.getElementById('nbDropdownMenu');
  if (!menu) return;

  if (forceClose) {
    menu.style.display = 'none';
    return;
  }

  const isVisible = menu.style.display === 'block';
  if (isVisible) {
    menu.style.display = 'none';
  } else {
    menu.style.display = 'block';
    renderNotebookDropdownMenu();
    const searchInput = document.getElementById('nbSearchInput');
    if (searchInput) {
      searchInput.value = '';
      setTimeout(() => searchInput.focus(), 50);
    }
  }
}

function onSearchNotebook(val) {
  renderNotebookDropdownMenu(val.trim());
}

async function openNotebook(notebookId) {
  toggleNotebookDropdown(true);
  try {
    setSaveStatus('saving');
    const res = await api(`/api/v1/notebooks/${notebookId}?includeCells=true`);
    if (!res.ok) {
      alert('Không thể mở notebook này (mã lỗi: ' + res.status + ')');
      setSaveStatus('error');
      return;
    }

    const data = await res.json();
    currentNotebook = {
      id: data.id,
      name: data.name,
      version: data.version || 0,
      role: data.role || 'OWNER',
      cells: data.cells || []
    };

    localStorage.setItem('last_opened_notebook_id', currentNotebook.id);

    // Cập nhật giao diện tiêu đề
    const titleEl = document.getElementById('notebookTitleInput');
    if (titleEl) {
      titleEl.value = currentNotebook.name;
    }

    // Nếu notebook không có cell nào: tạo sẵn 1 cell SQL rỗng
    if (!currentNotebook.cells || currentNotebook.cells.length === 0) {
      await createInitialCell(currentNotebook.id);
    } else {
      renderCellsList(currentNotebook.cells);
    }

    setSaveStatus('saved');
    if (window.initSessionForNotebook) {
      window.initSessionForNotebook(currentNotebook.id);
    }
  } catch (e) {
    console.error('Lỗi khi mở notebook:', e);
    setSaveStatus('error');
  }
}

async function createInitialCell(notebookId) {
  try {
    const res = await api(`/api/v1/notebooks/${notebookId}/cells`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        cellType: 'CODE',
        language: 'SQL',
        source: '',
        position: 0
      })
    });
    if (res.ok) {
      const cell = await res.json();
      currentNotebook.cells = [cell];
      renderCellsList(currentNotebook.cells);
    }
  } catch (e) {
    console.warn('Lỗi tạo cell khởi tạo:', e);
  }
}

async function createNewNotebook() {
  toggleNotebookDropdown(true);
  const name = 'notebook-' + (notebookList.length + 1);
  try {
    setSaveStatus('saving');
    const res = await api('/api/v1/notebooks', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: name,
        cells: [{
          cellType: 'CODE',
          language: 'SQL',
          source: '',
          position: 0
        }]
      })
    });

    if (res.ok) {
      const newNb = await res.json();
      await loadNotebookList();
      await openNotebook(newNb.id);
    } else {
      alert('Tạo notebook mới thất bại.');
      setSaveStatus('error');
    }
  } catch (e) {
    console.error('Lỗi tạo notebook:', e);
    setSaveStatus('error');
  }
}

async function renameCurrentNotebook(newName) {
  if (!currentNotebook || !newName || newName.trim() === '' || newName.trim() === currentNotebook.name) {
    if (currentNotebook) {
      document.getElementById('notebookTitleInput').value = currentNotebook.name;
    }
    return;
  }

  const trimmed = newName.trim();
  try {
    setSaveStatus('saving');
    const res = await api(`/api/v1/notebooks/${currentNotebook.id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: trimmed,
        version: currentNotebook.version
      })
    });

    if (res.ok) {
      const updated = await res.json();
      currentNotebook.name = updated.name;
      currentNotebook.version = updated.lockVersion;
      document.getElementById('notebookTitleInput').value = currentNotebook.name;
      await loadNotebookList();
      setSaveStatus('saved');
    } else {
      alert('Không thể đổi tên notebook.');
      document.getElementById('notebookTitleInput').value = currentNotebook.name;
      setSaveStatus('error');
    }
  } catch (e) {
    console.error('Lỗi đổi tên notebook:', e);
    document.getElementById('notebookTitleInput').value = currentNotebook.name;
    setSaveStatus('error');
  }
}

async function deleteNotebookFromList(notebookId, event) {
  if (event) event.stopPropagation();

  const nb = notebookList.find(n => n.id === notebookId);
  const nbName = nb ? nb.name : 'notebook này';
  if (!confirm(`Bạn có chắc chắn muốn xoá ${nbName}?`)) {
    return;
  }

  try {
    const res = await api(`/api/v1/notebooks/${notebookId}?purge=true`, { method: 'DELETE' });
    if (res.ok || res.status === 204) {
      await loadNotebookList();
      if (currentNotebook && currentNotebook.id === notebookId) {
        if (notebookList.length > 0) {
          await openNotebook(notebookList[0].id);
        } else {
          await createNewNotebook();
        }
      }
    } else {
      alert('Xoá notebook thất bại.');
    }
  } catch (e) {
    alert('Lỗi khi xoá notebook: ' + e.message);
  }
}

function triggerImportIpynb() {
  const fileInput = document.getElementById('ipynbFileInput');
  if (fileInput) {
    fileInput.value = '';
    fileInput.click();
  }
}

async function handleIpynbFileSelected(fileInput) {
  const file = fileInput.files && fileInput.files[0];
  if (!file) return;

  const reader = new FileReader();
  reader.onload = async (e) => {
    const content = e.target.result;
    let nbName = file.name.replace(/\.ipynb$/i, '');
    if (!nbName) nbName = 'imported-notebook';

    try {
      setSaveStatus('saving');
      const res = await api('/api/v1/notebooks:import', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          format: 'IPYNB',
          name: nbName,
          content: content
        })
      });

      if (res.ok) {
        const imported = await res.json();
        await loadNotebookList();
        await openNotebook(imported.id);
      } else {
        const err = await res.text();
        alert('Nhập file .ipynb thất bại: ' + err);
        setSaveStatus('error');
      }
    } catch (err) {
      alert('Lỗi nhập file: ' + err.message);
      setSaveStatus('error');
    }
  };
  reader.readAsText(file);
}

function exportCurrentNotebook() {
  if (!currentNotebook) return;
  const exportUrl = `/api/v1/notebooks/${currentNotebook.id}:export?includeOutputs=true`;
  const a = document.createElement('a');
  a.href = exportUrl;
  a.download = `${currentNotebook.name || 'notebook'}.ipynb`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
}

function setSaveStatus(status) {
  const el = document.getElementById('saveStatusIndicator');
  if (!el) return;

  if (status === 'saving') {
    el.innerHTML = '<i class="fa-solid fa-arrows-rotate fa-spin"></i> Đang lưu…';
    el.className = 'save-status saving';
  } else if (status === 'saved') {
    el.innerHTML = `<i class="fa-solid fa-check"></i> Đã lưu ${formatCurrentTime()}`;
    el.className = 'save-status saved';
  } else if (status === 'error') {
    el.innerHTML = '<i class="fa-solid fa-triangle-exclamation"></i> Lỗi lưu';
    el.className = 'save-status error';
  }
}

async function pollKyuubiStatus() {
  const el = document.getElementById('kyuubiStatusIndicator');
  if (!el) return;

  try {
    const res = await api('/api/v1/notebook-status');
    if (res.ok) {
      const data = await res.json();
      const isHealthy = data.kyuubi === 'HEALTHY';
      if (isHealthy) {
        el.className = 'kyuubi-status-badge healthy';
        el.innerHTML = '<span class="status-dot"></span> Kyuubi: Sẵn sàng';
        el.title = `Kyuubi: Sẵn sàng | Active runs: ${data.active || 0} | Sessions: ${data.activeSessions || 0}`;
      } else {
        el.className = 'kyuubi-status-badge unhealthy';
        el.innerHTML = '<span class="status-dot"></span> Kyuubi: Mất kết nối';
        el.title = 'Kyuubi REST API không phản hồi';
      }
    } else {
      el.className = 'kyuubi-status-badge unhealthy';
      el.innerHTML = '<span class="status-dot"></span> Kyuubi: Chưa sẵn sàng';
    }
  } catch (e) {
    el.className = 'kyuubi-status-badge unhealthy';
    el.innerHTML = '<span class="status-dot"></span> Kyuubi: Lỗi kết nối';
  }
}

function startKyuubiStatusPolling() {
  if (kyuubiStatusTimer) clearInterval(kyuubiStatusTimer);
  pollKyuubiStatus();
  kyuubiStatusTimer = setInterval(pollKyuubiStatus, 15000);
}
