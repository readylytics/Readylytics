package com.gregor.lauritz.healthdashboard.domain.repository

interface TransactionRunner {
    suspend fun <R> runInTransaction(block: suspend () -> R): R
}
