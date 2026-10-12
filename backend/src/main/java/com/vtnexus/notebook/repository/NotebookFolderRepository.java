package com.vtnexus.notebook.repository;

import com.vtnexus.notebook.model.NotebookFolder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotebookFolderRepository extends JpaRepository<NotebookFolder, String> {
    List<NotebookFolder> findByOwner(String owner);
    List<NotebookFolder> findByOwnerAndParentId(String owner, String parentId);
    List<NotebookFolder> findByOwnerAndParentIdIsNull(String owner);
    List<NotebookFolder> findByParentId(String parentId);
}
