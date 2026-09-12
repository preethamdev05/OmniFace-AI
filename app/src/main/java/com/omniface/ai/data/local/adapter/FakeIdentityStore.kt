package com.omniface.ai.data.local.adapter

import com.omniface.ai.ml.verification.domain.IdentityStore
import com.omniface.ai.ml.verification.domain.IdentityTemplate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fast, Deterministic In-Memory Adapter for IdentityStore Seam.
 *
 * Used for unit tests, benchmark harnesses, and headless execution.
 */
class FakeIdentityStore(
    initialTemplates: List<IdentityTemplate> = emptyList()
) : IdentityStore {

    private val _templatesFlow = MutableStateFlow(initialTemplates)

    override fun observeTemplates(): Flow<List<IdentityTemplate>> = _templatesFlow.asStateFlow()

    override suspend fun getTemplateCount(): Int = _templatesFlow.value.size

    fun setTemplates(templates: List<IdentityTemplate>) {
        _templatesFlow.value = templates
    }

    fun addTemplate(template: IdentityTemplate) {
        _templatesFlow.value = _templatesFlow.value + template
    }

    fun clear() {
        _templatesFlow.value = emptyList()
    }
}
