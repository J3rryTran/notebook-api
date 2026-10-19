// frontend/public/app/main.js
// Điểm khởi động ứng dụng và các trình lắng nghe sự kiện toàn cục

document.addEventListener('DOMContentLoaded', () => {
  // Kiểm tra xác thực và khởi tạo luồng dữ liệu
  checkAuthStatus(async (user) => {
    // 1. Tải danh sách notebook
    const list = await loadNotebookList();

    // 2. Mở notebook gần nhất hoặc notebook đầu tiên, hoặc tạo mới nếu chưa có
    const lastId = localStorage.getItem('last_opened_notebook_id');
    const targetNb = (lastId && list.find(n => n.id === lastId)) || (list.length > 0 ? list[0] : null);

    if (targetNb) {
      await openNotebook(targetNb.id);
    } else {
      await createNewNotebook();
    }

    // 3. Bắt đầu theo dõi trạng thái Kyuubi
    startKyuubiStatusPolling();
  });

  // Đóng dropdown khi click ra ngoài
  document.addEventListener('click', (e) => {
    const dropdown = document.getElementById('nbDropdownContainer');
    const menu = document.getElementById('nbDropdownMenu');
    if (menu && menu.style.display === 'block') {
      if (dropdown && !dropdown.contains(e.target)) {
        menu.style.display = 'none';
      }
    }
  });

  // Sự kiện phím Escape để đóng modal
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      const menu = document.getElementById('nbDropdownMenu');
      if (menu) menu.style.display = 'none';
      closeLogModal();
    }
  });
});
