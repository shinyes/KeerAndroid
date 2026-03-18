package site.lcyk.keer.viewmodel

import site.lcyk.keer.data.local.entity.MemoEntity
import site.lcyk.keer.util.ResolvedMemoQuote

data class MemoCardUiModel(
    val memo: MemoEntity,
    val resolvedQuote: ResolvedMemoQuote?,
)
