package com.aistock.assistant.data

import com.aistock.assistant.ai.PartyKind
import com.aistock.assistant.database.AppDatabase
import com.aistock.assistant.database.BusinessEntity
import com.aistock.assistant.database.ConversationEntity
import com.aistock.assistant.database.ConversationMessageEntity
import com.aistock.assistant.database.UserEntity
import com.aistock.assistant.utils.NameMatcher
import com.aistock.assistant.utils.Qty
import javax.inject.Inject
import javax.inject.Singleton

/** Business profile, the local user and PIN, saved conversations and optional sample data. */
@Singleton
class BusinessRepository @Inject constructor(
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val inventory: InventoryRepository,
    private val parties: PartyRepository,
) {
    private val meta get() = db.metaDao()
    private val conversations get() = db.conversationDao()

    fun observeBusiness() = meta.observeBusiness()
    fun observeUser() = meta.observeUser()
    suspend fun business() = meta.business()
    suspend fun user() = meta.user()

    suspend fun saveBusiness(name: String, owner: String, currencyCode: String, symbol: String, language: String) {
        val now = System.currentTimeMillis()
        val existing = meta.business()
        meta.saveBusiness(
            BusinessEntity(existing?.id ?: newId(), name.trim(), owner.trim(), currencyCode, symbol, language, existing?.createdAt ?: now, now)
        )
        val user = meta.user()
        if (user == null) meta.saveUser(UserEntity(newId(), owner.trim(), "", "", now, now))
        else meta.saveUser(user.copy(name = owner.trim(), updatedAt = now))
        settings.setCurrency(currencyCode, symbol)
        settings.setLanguage(language)
    }

    /** Sets or removes (blank) the app PIN. */
    suspend fun setPin(pin: String) {
        val now = System.currentTimeMillis()
        val user = meta.user() ?: UserEntity(newId(), meta.business()?.ownerName.orEmpty(), "", "", now, now)
        if (pin.isEmpty()) {
            meta.saveUser(user.copy(pinHash = "", pinSalt = "", updatedAt = now))
        } else {
            val salt = PinHasher.newSalt()
            meta.saveUser(user.copy(pinHash = PinHasher.hash(pin, salt), pinSalt = salt, updatedAt = now))
        }
    }

    suspend fun checkPin(pin: String): Boolean {
        val user = meta.user() ?: return false
        return PinHasher.verify(pin, user.pinSalt, user.pinHash)
    }

    /** After a restore the preferences follow the restored business profile. */
    suspend fun syncSettingsFromBusiness() {
        val b = meta.business() ?: return
        settings.setCurrency(b.currency, b.currencySymbol)
        settings.setLanguage(b.language)
        settings.setOnboardingDone(true)
    }

    // ------------------------------------------------------------ conversations

    suspend fun newConversation(title: String): String {
        val now = System.currentTimeMillis()
        val id = newId()
        conversations.insert(ConversationEntity(id, title.take(60), now, now))
        return id
    }

    suspend fun addMessage(conversationId: String, role: String, text: String, intent: String, metadata: String, title: String?) {
        val now = System.currentTimeMillis()
        conversations.insertMessage(ConversationMessageEntity(newId(), conversationId, role, text.take(4000), intent, metadata.take(4000), now))
        val c = conversations.byId(conversationId) ?: return
        conversations.touch(conversationId, title?.take(60) ?: c.title, now)
    }

    suspend fun messages(conversationId: String) = conversations.messages(conversationId)
    fun observeConversations() = conversations.observe()
    suspend fun conversationExists(id: String): Boolean = conversations.byId(id) != null
    suspend fun renameConversation(id: String, title: String) = conversations.rename(id, title.take(60))
    suspend fun deleteConversation(id: String) = conversations.delete(id)

    // ------------------------------------------------------------ sample data

    /** Only ever called when the user explicitly asks for demo data. */
    suspend fun loadSampleData(): Op<Unit> = db.op {
        // Existing records are checked first: a failed insert would roll back the whole batch.
        val categoryIds = HashMap<String, String>()
        for (c in inventory.activeCategories()) categoryIds[NameMatcher.key(c.name)] = c.id
        suspend fun category(name: String): String =
            categoryIds[NameMatcher.key(name)] ?: inventory.saveCategory(null, name, "").orThrow().id.also { categoryIds[NameMatcher.key(name)] = it }

        val samples = listOf(
            ProductInput("Blue Shirt", categoryId = category("Garments"), unit = "PCS", purchasePrice = 30_000, sellingPrice = 45_000, minimumStock = Qty.of(10), openingStock = Qty.of(50)),
            ProductInput("Black Shirt", categoryId = category("Garments"), unit = "PCS", purchasePrice = 32_000, sellingPrice = 48_000, minimumStock = Qty.of(10), openingStock = Qty.of(8)),
            ProductInput("Cotton Yarn", categoryId = category("Yarn"), unit = "KG", purchasePrice = 18_000, sellingPrice = 22_000, minimumStock = Qty.of(25), openingStock = Qty.of(120)),
            ProductInput("A4 Paper", categoryId = category("Stationery"), unit = "BOX", purchasePrice = 25_000, sellingPrice = 30_000, minimumStock = Qty.of(10), openingStock = Qty.of(40)),
        )
        val have = inventory.activeProducts().map { NameMatcher.key(it.name) }.toHashSet()
        for (s in samples) if (NameMatcher.key(s.name) !in have) inventory.createProduct(s).orThrow()
        if (parties.list(PartyKind.CUSTOMER).none { NameMatcher.key(it.name) == NameMatcher.key("Kumar Traders") }) {
            parties.create(PartyKind.CUSTOMER, PartyInput("Kumar Traders", phone = "9876543210", address = "Tiruppur")).orThrow()
        }
        if (parties.list(PartyKind.SUPPLIER).none { NameMatcher.key(it.name) == NameMatcher.key("ABC Textiles") }) {
            parties.create(PartyKind.SUPPLIER, PartyInput("ABC Textiles", phone = "9123456780", address = "Coimbatore")).orThrow()
        }
    }

    /** Erases everything and returns the app to first launch. */
    suspend fun resetAll() {
        db.op {
            val sdb = db.openHelper.writableDatabase
            for (t in AppDatabase.TABLES.reversed() + AppDatabase.LOCAL_TABLES) sdb.execSQL("DELETE FROM `$t`")
        }
        settings.reset()
    }
}
