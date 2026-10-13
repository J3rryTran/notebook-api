package com.vtnexus.notebook.document.repository;

import com.vtnexus.notebook.document.model.Notebook;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NotebookRepository extends JpaRepository<Notebook, String> {
    
    Optional<Notebook> findByIdAndDeletedAtIsNull(String id);

    @Query("SELECT n FROM Notebook n WHERE n.deletedAt IS NULL AND (n.owner = :user OR EXISTS (SELECT p FROM NotebookPermission p WHERE p.notebookId = n.id AND p.principalId = :user))")
    List<Notebook> findAccessibleNotebooks(@Param("user") String user, Pageable pageable);

    @Query("SELECT n FROM Notebook n WHERE n.deletedAt IS NULL AND n.folderId = :folderId AND (n.owner = :user OR EXISTS (SELECT p FROM NotebookPermission p WHERE p.notebookId = n.id AND p.principalId = :user))")
    List<Notebook> findAccessibleNotebooksByFolder(@Param("user") String user, @Param("folderId") String folderId, Pageable pageable);

    @Query("SELECT n FROM Notebook n WHERE n.deletedAt IS NULL AND (n.owner = :user OR EXISTS (SELECT p FROM NotebookPermission p WHERE p.notebookId = n.id AND p.principalId = :user)) AND (LOWER(n.name) LIKE LOWER(CONCAT('%', :query, '%')) OR LOWER(n.description) LIKE LOWER(CONCAT('%', :query, '%')))")
    List<Notebook> searchNotebooks(@Param("user") String user, @Param("query") String query, Pageable pageable);
}
