package com.taxiapp.server.repository

import com.taxiapp.server.model.sector.Sector
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface SectorRepository : JpaRepository<Sector, Long>