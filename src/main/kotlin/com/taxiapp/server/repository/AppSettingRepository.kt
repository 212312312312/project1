package com.taxiapp.server.repository

import com.taxiapp.server.model.setting.AppSetting
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface AppSettingRepository : JpaRepository<AppSetting, String>