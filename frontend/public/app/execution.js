// frontend/public/app/execution.js
// Quản lý Kyuubi session, thực thi cell, long-polling events, hiển thị kết quả và huỷ/restart runtime

let currentSessionId = null;
let runningExecutionId = null;
let runningCellId = null;
let cancelRequested = false;
let isBatchRunning = false;

window.initSessionForNotebook = function(notebookId) {
  currentSessionId = null;
  runningExecutionId = null;
  runningCellId = null;
  cancelRequested = false;
  isBatchRunning = false;
};

async function getOrCreateSession(notebookId) {
  if (currentSessionId) {
    return currentSessionId;
  }

  const res = await api(`/api/v1/notebooks/${notebookId}/sessions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({})
  });

  if (!res.ok) {
    throw new Error(`Không thể khởi tạo Kyuubi session (Mã lỗi ${res.status})`);
  }

  const data = await res.json();
  const session = data.session || data;
  currentSessionId = session.id;
  return currentSessionId;
}

async function restartSession() {
  if (!confirm('Khởi động lại session Spark engine sẽ xoá các biến tạm trong bộ nhớ. Bạn có muốn tiếp tục?')) {
    return;
  }

  setSaveStatus('saving');
  try {
    if (currentSessionId) {
      await api(`/api/v1/notebook-runtimes/${currentSessionId}:restart`, { method: 'POST' });
    } else if (currentNotebook) {
      await getOrCreateSession(currentNotebook.id);
    }
    alert('Session Spark engine đã được khởi động lại thành công.');
    setSaveStatus('saved');
    pollKyuubiStatus();
  } catch (e) {
    alert('Lỗi khởi động lại session: ' + e.message);
    setSaveStatus('error');
  }
}

async function stopExecution() {
  cancelRequested = true;
  isBatchRunning = false;

  if (runningExecutionId) {
    try {
      await api(`/api/v1/executions/${runningExecutionId}:cancel`, { method: 'POST' });
    } catch (e) {
      console.warn('Lỗi huỷ execution:', e);
    }
  }

  if (currentSessionId) {
    try {
      await api(`/api/v1/notebook-runtimes/${currentSessionId}:interrupt`, { method: 'POST' });
    } catch (e) {
      console.warn('Lỗi ngắt runtime:', e);
    }
  }

  if (runningCellId) {
    const statusEl = document.getElementById(`status-${runningCellId}`);
    if (statusEl) statusEl.innerHTML = '<span class="status-badge canceled">Đã yêu cầu dừng</span>';
  }
}

async function runCell(cellId, event) {
  if (event) event.stopPropagation();
  if (!currentNotebook) return;

  if (runningExecutionId && runningCellId === cellId) {
    return;
  }

  setActiveCell(cellId);
  const card = document.getElementById(`card-${cellId}`);
  const countEl = document.getElementById(`count-${cellId}`);
  const statusEl = document.getElementById(`status-${cellId}`);
  const outputEl = document.getElementById(`output-${cellId}`);

  if (countEl) countEl.innerText = '[*]';
  if (statusEl) statusEl.innerHTML = '<span class="status-badge running"><i class="fa-solid fa-spinner fa-spin"></i> Đang chuẩn bị…</span>';

  // Lưu nội dung cell hiện tại trước khi submit
  await saveCellSource(cellId, true);

  const startTime = Date.now();
  let timerInterval = null;

  try {
    cancelRequested = false;
    runningCellId = cellId;

    // 1. Đảm bảo session Kyuubi sẵn sàng
    if (statusEl) statusEl.innerHTML = '<span class="status-badge running"><i class="fa-solid fa-spinner fa-spin"></i> Khởi tạo session…</span>';
    const sessionId = await getOrCreateSession(currentNotebook.id);

    // 2. Submit execution
    if (statusEl) statusEl.innerHTML = '<span class="status-badge running"><i class="fa-solid fa-spinner fa-spin"></i> Đang gửi lệnh…</span>';
    const subRes = await api(`/api/v1/notebook-sessions/${sessionId}/executions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ cellId: cellId })
    });

    if (!subRes.ok) {
      const errBody = await subRes.text();
      throw new Error(`Gửi lệnh thất bại: ${errBody}`);
    }

    const execDto = await subRes.json();
    runningExecutionId = execDto.id;

    // Đếm thời gian chạy
    timerInterval = setInterval(() => {
      const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
      if (statusEl && !cancelRequested) {
        statusEl.innerHTML = `<span class="status-badge running"><i class="fa-solid fa-spinner fa-spin"></i> Đang chạy (${elapsed}s)</span>`;
      }
    }, 200);

    // 3. Long-poll trạng thái execution
    let finalStatus = execDto.status;
    while (finalStatus === 'PENDING' || finalStatus === 'RUNNING') {
      if (cancelRequested) {
        finalStatus = 'CANCELED';
        break;
      }

      try {
        const pollRes = await api(`/api/v1/executions/${runningExecutionId}/events?waitMillis=3000`);
        if (pollRes.status === 200) {
          const pollData = await pollRes.json();
          finalStatus = pollData.status;
        } else if (pollRes.status === 204) {
          // Timeout, kiểm tra lại qua endpoint chi tiết
          const checkRes = await api(`/api/v1/executions/${runningExecutionId}`);
          if (checkRes.ok) {
            const checkData = await checkRes.json();
            finalStatus = checkData.status;
          }
        } else {
          break;
        }
      } catch (pollErr) {
        console.warn('Lỗi poll event:', pollErr);
        await new Promise(r => setTimeout(r, 1000));
      }
    }

    clearInterval(timerInterval);
    const durationMs = Date.now() - startTime;

    // 4. Lấy kết quả và render tương ứng
    await handleExecutionCompleted(runningExecutionId, cellId, finalStatus, durationMs);

  } catch (err) {
    if (timerInterval) clearInterval(timerInterval);
    console.error('Lỗi thực thi cell:', err);
    if (statusEl) statusEl.innerHTML = '<span class="status-badge failed">Lỗi</span>';
    if (outputEl) {
      outputEl.innerHTML = renderErrorOutput({
        ename: 'ExecutionFailure',
        evalue: err.message
      }, 'error-' + Date.now());
    }
    if (countEl) countEl.innerText = '[!]';
  } finally {
    runningExecutionId = null;
    runningCellId = null;
  }
}

async function handleExecutionCompleted(executionId, cellId, status, durationMs) {
  const countEl = document.getElementById(`count-${cellId}`);
  const statusEl = document.getElementById(`status-${cellId}`);
  const outputEl = document.getElementById(`output-${cellId}`);

  // Lấy chi tiết execution để có executionCount
  let execDto = null;
  try {
    const detailRes = await api(`/api/v1/executions/${executionId}`);
    if (detailRes.ok) {
      execDto = await detailRes.json();
    }
  } catch (e) {}

  if (execDto && execDto.executionCount !== undefined && countEl) {
    countEl.innerText = `[${execDto.executionCount}]`;
  } else if (countEl) {
    countEl.innerText = '[1]';
  }

  if (status === 'SUCCEEDED') {
    if (statusEl) {
      statusEl.innerHTML = `<span class="status-badge succeeded"><i class="fa-solid fa-check"></i> Xong (${formatDuration(durationMs)})</span>`;
    }

    // Lấy results
    let resultsRendered = false;
    try {
      const resRes = await api(`/api/v1/executions/${executionId}/results`);
      if (resRes.ok) {
        const text = await resRes.text();
        if (text && text.trim() !== '' && text.trim() !== '[]') {
          const parsed = JSON.parse(text);
          outputEl.innerHTML = renderParsedOutput(parsed, executionId);
          resultsRendered = true;
        }
      }
    } catch (e) {
      console.warn('Không thể parse results:', e);
    }

    // Nếu results rỗng, lấy outputs
    if (!resultsRendered) {
      try {
        const outRes = await api(`/api/v1/executions/${executionId}/outputs`);
        if (outRes.ok) {
          const outText = await outRes.text();
          if (outText && outText.trim() !== '' && outText.trim() !== '[]') {
            const parsedOut = JSON.parse(outText);
            outputEl.innerHTML = renderParsedOutput(parsedOut, executionId);
            resultsRendered = true;
          }
        }
      } catch (e) {}
    }

    if (!resultsRendered) {
      outputEl.innerHTML = renderStreamOutput('Truy vấn thực thi thành công (không có kết quả trả về).', { durationMs });
    }

  } else if (status === 'FAILED') {
    if (statusEl) {
      statusEl.innerHTML = `<span class="status-badge failed"><i class="fa-solid fa-xmark"></i> Lỗi (${formatDuration(durationMs)})</span>`;
    }

    let errorData = null;
    try {
      const outRes = await api(`/api/v1/executions/${executionId}/outputs`);
      if (outRes.ok) {
        const outJson = await outRes.json();
        errorData = outJson;
      }
    } catch (e) {}

    if (!errorData) {
      errorData = {
        ename: 'ExecutionFailed',
        evalue: execDto && execDto.errorMessage ? execDto.errorMessage : 'Thực thi thất bại trên Kyuubi.'
      };
    }

    outputEl.innerHTML = renderErrorOutput(errorData, executionId);

  } else if (status === 'CANCELED') {
    if (statusEl) {
      statusEl.innerHTML = '<span class="status-badge canceled">Đã huỷ</span>';
    }
    outputEl.innerHTML = renderStreamOutput('Lệnh thực thi đã bị huỷ bởi người dùng.');
  } else {
    if (statusEl) {
      statusEl.innerHTML = `<span class="status-badge failed">${status}</span>`;
    }
    outputEl.innerHTML = renderStreamOutput(`Trạng thái thực thi: ${status}`);
  }
}

async function runAllCells() {
  if (!currentNotebook || !currentNotebook.cells || currentNotebook.cells.length === 0) {
    return;
  }

  isBatchRunning = true;
  cancelRequested = false;

  for (let i = 0; i < currentNotebook.cells.length; i++) {
    if (!isBatchRunning || cancelRequested) {
      break;
    }
    const cell = currentNotebook.cells[i];
    await runCell(cell.id);
  }

  isBatchRunning = false;
}
