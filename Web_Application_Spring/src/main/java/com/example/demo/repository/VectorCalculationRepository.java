package com.example.demo.repository;

import com.example.demo.model.VectorCalculation; // Import your Entity

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

// [Layer 4]: A repository is a bridge between Spring and Database.
@Repository
public interface VectorCalculationRepository extends JpaRepository<VectorCalculation, Long> { 
    /* 
        - extends JpaRepository<VectorCalculation, Long>: Spring automatically gives us a full suite of CRUD (Create, Read, Update, Delete) out of the box.
        - can start calling these methods immediately aftr injecting into service layer: repository.save(vector), repository.findById(id), repository.deleteById(id).
        - This works across databases: Oracle DB, PostgreSQL, MySQL.
        - Hibernate is an Object Relational Mapper (ORM) that examines the @Entity annotations and generates the exact database SQL queries needed at runtime.
        - Hibernate speaks in tables and rows (SQL) and is a universal translator that translates  repository.save(vector) into the exact dialect of SQL that Oracle DB, PostgreSQL, MySQL requires.
    */

    // Spring generates the SQL: SELECT * FROM vector_calculation WHERE magnitude > ?
    List<VectorCalculation> findByMagnitudeGreaterThan(Double value);

    // For very complex SQL, you can explicitly write it yourself:
    // @Query("SELECT v FROM VectorCalculation v WHERE v.name LIKE %:keyword%")
    // List<VectorCalculation> searchByName(String keyword);

    // Assuming you want to search by ID as a string, or change 'id' to whatever string field you add to your entity!
    @Query("SELECT v FROM VectorCalculation v WHERE CAST(v.id AS string) LIKE CONCAT('%', :keyword, '%')")
    List<VectorCalculation> searchByIdKeyword(@Param("keyword") String keyword);
}