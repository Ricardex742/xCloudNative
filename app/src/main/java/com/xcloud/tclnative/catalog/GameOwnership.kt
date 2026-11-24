package com.xcloud.tclnative.catalog

import com.xcloud.tclnative.i18n.UiText

enum class Ownership { OWNED, NOT_OWNED, UNKNOWN }
enum class OwnershipFilter(private val source: String) {
    ALL("Todos os jogos"), OWNED("Adquiridos"), NOT_OWNED("Não adquiridos"), UNKNOWN("Propriedade não confirmada");
    val label get()=UiText.text(source)
    fun accepts(value: Ownership)=when(this) {
        ALL -> true
        OWNED -> value==Ownership.OWNED
        NOT_OWNED -> value==Ownership.NOT_OWNED
        UNKNOWN -> value==Ownership.UNKNOWN
    }
}

/** Exact product identity: neither Game Pass access nor public galleries prove ownership. */
private val ownershipId=Regex("[A-Z0-9]{12}")
internal fun ownershipProductKey(id: String?): String?=id?.let(GamePassCatalog::productKey)?.takeIf(ownershipId::matches)
fun CatalogCard.ownershipProductId(): String?=ownershipProductKey(product?.productId ?: game?.productId)

/** Reads direct purchase rights from ContentAccessAllV1Response.productsByProductId. */
internal object ContentAccessOwnership {
    private const val OWNED = 1L
    private val accountId = Regex("[1-9][0-9]{0,19}")

    fun decode(bytes: ByteArray, requested: Set<String>): Map<String, Ownership> {
        require(bytes.isNotEmpty() && bytes.size <= ContentAccessPlaytime.MAX_BYTES)
        val root = ContentAccessWire(bytes)
        val accounts = mutableSetOf<String>()
        val seen = mutableSetOf<String>()
        val rights = mutableMapOf<String, Ownership>()
        while (root.more()) {
            val tag = root.tag()
            when (tag ushr 3) {
                2 -> {
                    val (key, data) = root.message(tag).entry(maxKeyBytes = 128)
                    val product = checkNotNull(data)
                    val id = ownershipProductKey(key) ?: continue
                    check(seen.add(id))
                    val access = product.directAccess(accounts)
                    if (id in requested && access != null)
                        rights[id] = if (access and OWNED != 0L) Ownership.OWNED else Ownership.NOT_OWNED
                }
                else -> root.skip(tag)
            }
        }
        return rights
    }

    private fun MutableSet<String>.addAccount(id: String) {
        require(accountId.matches(id) && (id.length < 20 || id <= "18446744073709551615"))
        add(id)
        check(size == 1)
    }

    private fun ContentAccessWire.directAccess(accounts: MutableSet<String>): Long? {
        var flags: Long? = null
        while (more()) {
            val tag = tag()
            if (tag ushr 3 != 2) {
                skip(tag)
                continue
            }
            check(flags == null)
            val (account, access) = message(tag).entry()
            checkNotNull(access)
            accounts.addAccount(account)
            var direct: Long? = null
            while (access.more()) {
                val field = access.tag()
                if (field ushr 3 == 1) {
                    check(direct == null)
                    direct = access.number(field).also { require(it in 0..0xffffffffL) }
                } else access.skip(field)
            }
            flags = direct ?: 0L
        }
        return flags
    }
}
