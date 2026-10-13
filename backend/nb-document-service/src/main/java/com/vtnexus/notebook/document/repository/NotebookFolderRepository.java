package com.vtnexus.notebook.document.repository;

import com.vtnexus.notebook.document.model.NotebookFolder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NotebookFolderRepository extends JpaRepository<NotebookFolder, String> {
    List<NotebookFolder> findByOwnerAndDeletedAtIsNull(String owner);
    List<NotebookFolder> findByOwnerAndParentIdAndDeletedAtIsNull(String owner, String parentId);
    List<NotebookFolder> findByOwnerAndParentIdIsNullAndDeletedAtIsNull(String owner);
    List<NotebookFolder> findByParentIdAndDeletedAtIsNull(String parentId);
    Optional<NotebookFolder> findByIdAndDeletedAtIsNull(String id);
}
