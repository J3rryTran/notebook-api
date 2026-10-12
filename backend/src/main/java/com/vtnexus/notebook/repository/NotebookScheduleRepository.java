package com.vtnexus.notebook.repository;

import com.vtnexus.notebook.model.NotebookSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NotebookScheduleRepository extends JpaRepository<NotebookSchedule, String> {
}
