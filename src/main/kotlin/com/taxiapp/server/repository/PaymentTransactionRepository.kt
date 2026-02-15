package com.taxiapp.server.repository

import com.taxiapp.server.model.finance.PaymentTransaction
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface PaymentTransactionRepository : JpaRepository<PaymentTransaction, Long>