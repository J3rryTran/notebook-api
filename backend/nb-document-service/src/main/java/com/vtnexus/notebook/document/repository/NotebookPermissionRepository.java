package com.vtnexus.notebook.document.repository;

import com.vtnexus.notebook.document.model.NotebookPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NotebookPermissionRepository extends JpaRepository<NotebookPermission, Long> {
    List<NotebookPermission> findByNotebookId(String notebookId);
    Optional<NotebookPermission> findByNotebookIdAndPrincipalId(String notebookId, String principalId);
    void deleteByNotebookId(String notebookId);
}
