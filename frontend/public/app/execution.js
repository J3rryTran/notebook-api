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
    let pollError = null;
    let unavailableRetries = 0;
    const maxUnavailableRetries = 5;
    const maxWaitMs = (window.notebookConfig && window.notebookConfig.executionMaxWait) || 30 * 60 * 1000;

    while (finalStatus === 'PENDING' || finalStatus === 'RUNNING') {
      if (cancelRequested) {
        finalStatus = 'CANCELED';
        break;
      }

      if (Date.now() - startTime > maxWaitMs) {
        pollError = {
          ename: 'TimeoutError',
          evalue: `Quá thời gian chờ thực thi (${Math.round(maxWaitMs / 60000)} phút). Bạn có thể kiểm tra lại trạng thái cell.`
        };
        finalStatus = 'TIMEOUT';
        break;
      }

      try {
        const pollRes = await api(`/api/v1/executions/${runningExecutionId}/events?waitMillis=3000`);
        if (pollRes.status === 200) {
          unavailableRetries = 0;
          const pollData = await pollRes.json();
          finalStatus = pollData.status;
        } else if (pollRes.status === 204) {
          unavailableRetries = 0;
          // Timeout, kiểm tra lại qua endpoint chi tiết
          const checkRes = await api(`/api/v1/executions/${runningExecutionId}`);
          if (checkRes.ok) {
            const checkData = await checkRes.json();
            finalStatus = checkData.status;
          }
        } else if (pollRes.status === 401) {
          showLoginModal('Phiên đăng nhập đã hết hạn hoặc không hợp lệ.');
          pollError = {
            ename: 'Unauthorized',
            evalue: '401 UNAUTHORIZED: Phiên đăng nhập đã hết hạn.'
          };
          finalStatus = 'ERROR';
          break;
        } else if (pollRes.status === 503) {
          unavailableRetries++;
          if (unavailableRetries <= maxUnavailableRetries) {
            const backoffMs = Math.min(1000 * Math.pow(2, unavailableRetries - 1), 30000);
            if (statusEl && !cancelRequested) {
              statusEl.innerHTML = `<span class="status-badge running"><i class="fa-solid fa-spinner fa-spin"></i> Kyuubi tạm không phản hồi, đang thử lại (${unavailableRetries}/${maxUnavailableRetries})</span>`;
            }
            await new Promise(r => setTimeout(r, backoffMs));
            continue;
          } else {
            pollError = {
              ename: 'KyuubiUnavailable',
              evalue: `503 KYUUBI_UNAVAILABLE: Kyuubi tạm không phản hồi sau ${maxUnavailableRetries} lần thử lại.`
            };
            finalStatus = 'ERROR';
            break;
          }
        } else {
          unavailableRetries = 0;
          let errCode = 'UNKNOWN_ERROR';
          let errMsg = `HTTP ${pollRes.status}`;
          try {
            const errJson = await pollRes.json();
            if (errJson.code) errCode = errJson.code;
            if (errJson.message) errMsg = errJson.message;
          } catch (_) {
            try {
              const text = await pollRes.text();
              if (text && text.trim()) errMsg = text.trim();
            } catch (__) {}
          }
          pollError = {
            ename: 'PollError',
            evalue: `${pollRes.status} ${errCode}: ${errMsg}`
          };
          finalStatus = 'ERROR';
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
    await handleExecutionCompleted(runningExecutionId, cellId, finalStatus, durationMs, pollError);

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
    if (typeof pollKyuubiStatus === 'function') {
      pollKyuubiStatus();
    }
  }
}

async function handleExecutionCompleted(executionId, cellId, status, durationMs, pollError = null) {
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

    const pageSize = (window.notebookConfig && window.notebookConfig.pageSize) || 200;
    let resultsRendered = false;
    let savedOutput = null;

    // Lấy results (bảng SQL)
    try {
      const resRes = await api(`/api/v1/executions/${executionId}/results?offset=0&limit=${pageSize}`);
      if (resRes.ok) {
        const text = await resRes.text();
        if (text && text.trim() !== '' && text.trim() !== '[]') {
          const parsed = JSON.parse(text);
          outputEl.innerHTML = renderParsedOutput(parsed, cellId, executionId, { durationMs });
          resultsRendered = true;
          savedOutput = parsed;
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
            outputEl.innerHTML = renderParsedOutput(parsedOut, cellId, executionId, { durationMs });
            resultsRendered = true;
            savedOutput = parsedOut;
          }
        }
      } catch (e) {}
    }

    if (!resultsRendered) {
      const defaultOut = { output_type: 'stream', name: 'stdout', text: 'Truy vấn thực thi thành công (không có kết quả trả về).' };
      outputEl.innerHTML = renderStreamOutput(defaultOut.text, { durationMs });
      savedOutput = defaultOut;
    }

    if (savedOutput) {
      const outputsArray = Array.isArray(savedOutput) ? savedOutput : [savedOutput];
      const count = (execDto && execDto.executionCount !== undefined) ? execDto.executionCount : 1;
      saveCellOutputs(cellId, outputsArray, count);
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
        output_type: 'error',
        ename: 'ExecutionFailed',
        evalue: execDto && execDto.errorMessage ? execDto.errorMessage : 'Thực thi thất bại trên Kyuubi.'
      };
    }

    outputEl.innerHTML = renderErrorOutput(errorData, executionId);
    const errArray = Array.isArray(errorData) ? errorData : [errorData];
    const count = (execDto && execDto.executionCount !== undefined) ? execDto.executionCount : null;
    saveCellOutputs(cellId, errArray, count);

  } else if (status === 'CANCELED') {
    if (statusEl) {
      statusEl.innerHTML = '<span class="status-badge canceled">Đã huỷ</span>';
    }
    outputEl.innerHTML = renderStreamOutput('Lệnh thực thi đã bị huỷ bởi người dùng.');
  } else if (status === 'ERROR') {
    if (statusEl) {
      statusEl.innerHTML = '<span class="status-badge failed">Lỗi</span>';
    }
    outputEl.innerHTML = renderErrorOutput(pollError || {
      ename: 'PollError',
      evalue: (execDto && execDto.errorMessage) ? execDto.errorMessage : 'Lỗi trong quá trình theo dõi thực thi.'
    }, executionId || ('error-' + Date.now()));
  } else if (status === 'TIMEOUT') {
    if (statusEl) {
      statusEl.innerHTML = '<span class="status-badge failed">Hết giờ</span>';
    }
    outputEl.innerHTML = `<div class="stream-output error-text" style="padding: 10px; margin-bottom: 8px;">${escapeHtml(pollError ? pollError.evalue : 'Quá thời gian chờ thực thi.')}</div>
      <button class="btn btn-secondary btn-sm" onclick="checkExecutionStatus('${executionId}', '${cellId}')"><i class="fa-solid fa-arrows-rotate"></i> Kiểm tra lại</button>`;
  } else {
    if (statusEl) {
      statusEl.innerHTML = `<span class="status-badge failed">${status}</span>`;
    }
    const detailMsg = (execDto && execDto.errorMessage) ? `: ${execDto.errorMessage}` : '';
    outputEl.innerHTML = renderStreamOutput(`Trạng thái thực thi: ${status}${detailMsg}`);
  }
}

async function checkExecutionStatus(executionId, cellId) {
  try {
    const res = await api(`/api/v1/executions/${executionId}`);
    if (res.ok) {
      const data = await res.json();
      await handleExecutionCompleted(executionId, cellId, data.status, 0);
    }
  } catch (e) {
    console.error('Không thể kiểm tra trạng thái execution:', e);
  }
}
window.checkExecutionStatus = checkExecutionStatus;

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
