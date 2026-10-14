package com.vtnexus.notebook.store.repository;

import com.vtnexus.notebook.store.model.NotebookRevision;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NotebookRevisionRepository extends JpaRepository<NotebookRevision, String> {
    List<NotebookRevision> findByNotebookIdOrderByRevisionNumberDesc(String notebookId, Pageable pageable);
    Optional<NotebookRevision> findByNotebookIdAndRevisionNumber(String notebookId, Long revisionNumber);

    @Query("SELECT MAX(r.revisionNumber) FROM NotebookRevision r WHERE r.notebookId = :notebookId")
    Long findMaxRevisionNumberByNotebookId(@Param("notebookId") String notebookId);

    @Query("SELECT COUNT(r) FROM NotebookRevision r WHERE r.notebookId = :notebookId AND r.revisionType = 'AUTO'")
    long countAutoRevisions(@Param("notebookId") String notebookId);

    @Query("SELECT r FROM NotebookRevision r WHERE r.notebookId = :notebookId AND r.revisionType = 'AUTO' ORDER BY r.revisionNumber ASC")
    List<NotebookRevision> findAutoRevisionsOrderByRevisionNumberAsc(@Param("notebookId") String notebookId, Pageable pageable);

    void deleteByNotebookId(String notebookId);
}

