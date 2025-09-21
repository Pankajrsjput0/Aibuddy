package com.example.aiagent

data class PlanStep(val stepId: Int, val type: String, val description: String, val params: Map<String, Any>?, val requiresConfirmation: Boolean = false)