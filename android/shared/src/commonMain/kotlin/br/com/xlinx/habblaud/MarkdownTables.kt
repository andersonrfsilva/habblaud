package br.com.xlinx.habblaud

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.MarkdownComponent
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.model.MarkdownDimens
import com.mikepenz.markdown.model.markdownDimens

/** Coluna um pouco mais larga que o padrão da biblioteca (160 dp); a tabela rola na horizontal quando não cabe. */
@Composable
internal fun chatMarkdownDimens(): MarkdownDimens = markdownDimens(tableCellWidth = 180.dp)

/** A biblioteca corta cada célula em uma linha com reticências; em tela estreita o texto precisa quebrar. */
internal val wrappingTable: MarkdownComponent = { model ->
    MarkdownTable(
        content = model.content,
        node = model.node,
        style = model.typography.table,
        headerBlock = { content, header, width, style ->
            MarkdownTableHeader(content, header, width, style, verticalAlignment = Alignment.Top, maxLines = Int.MAX_VALUE, overflow = TextOverflow.Clip)
        },
        rowBlock = { content, row, width, style ->
            MarkdownTableRow(content, row, width, style, verticalAlignment = Alignment.Top, maxLines = Int.MAX_VALUE, overflow = TextOverflow.Clip)
        },
    )
}
