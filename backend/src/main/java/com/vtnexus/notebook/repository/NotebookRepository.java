package com.vtnexus.notebook.repository;

import com.vtnexus.notebook.model.Notebook;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotebookRepository extends JpaRepository<Notebook, String> {
    List<Notebook> findByOwner(String owner, Pageable pageable);
    List<Notebook> findByOwnerAndFolderId(String owner, String folderId, Pageable pageable);
    List<Notebook> findByFolderId(String folderId);

    @Query("SELECT n FROM Notebook n WHERE (n.owner = :user OR EXISTS (SELECT p FROM NotebookPermission p WHERE p.notebookId = n.id AND p.principalId = :user)) AND (LOWER(n.name) LIKE LOWER(CONCAT('%', :query, '%')) OR LOWER(n.description) LIKE LOWER(CONCAT('%', :query, '%')))")
    List<Notebook> searchNotebooks(@Param("user") String user, @Param("query") String query, Pageable pageable);
}
