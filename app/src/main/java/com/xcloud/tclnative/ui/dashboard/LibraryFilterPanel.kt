package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import com.xcloud.tclnative.catalog.OwnershipFilter
import com.xcloud.tclnative.i18n.UiText

/** Vertical, scrollable rows keep every filter reachable on TVs and narrow screens. */
object LibraryFilterPanel {
    fun show(context: Context,current: OwnershipFilter,information: String,
        choose: (OwnershipFilter) -> Unit,refresh: () -> Unit,onBack: () -> Unit): ConsoleDialog =
        ConsoleDialog(context,UiText.text("Filtrar biblioteca")).apply {
            body.addView(text(UiText.text("Filtra os jogos deste catálogo pelas licenças da conta. Acesso por assinatura não significa propriedade. Edições e produtos não confirmados ficam separados.\n{0}",UiText.nested(information)),20,color=DashboardTheme.muted))
            OwnershipFilter.entries.forEach { filter ->
                val row=addMenuItem((if(filter==current) "✓ " else "○ ")+filter.label) { choose(filter) }
                if(filter==current) focusFirst(row)
            }
            addMenuItem(UiText.text("Atualizar licenças"),refresh)
            addAction(UiText.text("Voltar"),onBack);setOnCancelListener { onBack() };show()
        }
}
