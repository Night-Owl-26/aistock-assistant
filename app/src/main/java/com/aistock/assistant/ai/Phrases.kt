package com.aistock.assistant.ai

/** Every sentence the assistant says, in English and Tamil. */
class Phrases(private val ta: Boolean) {
    private fun t(en: String, tam: String) = if (ta) tam else en

    fun didntCatch() = t("I couldn't hear that. Please try again.", "சரியாகக் கேட்கவில்லை. மீண்டும் சொல்லுங்கள்.")
    fun unknown() = t(
        "I didn't understand that. Try something like \"Add 50 pieces of Blue Shirt\" or \"What were today's sales?\"",
        "புரியவில்லை. \"Blue Shirt 50 pieces add pannunga\" அல்லது \"இன்றைய விற்பனை எவ்வளவு?\" போல சொல்லுங்கள்."
    )
    fun notSure() = t("I'm not sure what you want me to do, so I haven't changed anything. Please say it another way.",
        "நீங்கள் என்ன சொல்கிறீர்கள் என்று உறுதியாக தெரியவில்லை. எதுவும் மாற்றவில்லை. வேறு விதமாக சொல்லுங்கள்.")
    fun invalid() = t("That command doesn't look right, so I haven't changed anything.", "அந்த கட்டளை சரியாக இல்லை. எதுவும் மாற்றவில்லை.")
    fun saveFailed() = t("Something went wrong while saving. Your data was not changed.", "சேமிக்கும்போது தவறு ஏற்பட்டது. உங்கள் தரவு மாற்றப்படவில்லை.")
    fun cancelled() = t("Okay, cancelled. Nothing was changed.", "சரி, ரத்து செய்யப்பட்டது. எதுவும் மாற்றவில்லை.")
    fun nothingToConfirm() = t("There is nothing waiting for confirmation.", "உறுதிப்படுத்த எதுவும் இல்லை.")
    fun paymentGuide() = t("To record money received from a customer or paid to a supplier, open Transactions and choose Payments.",
        "வாடிக்கையாளரிடம் பெற்ற பணம் அல்லது சப்ளையருக்கு கொடுத்த பணத்தைப் பதிவு செய்ய, Transactions-ல் Payments-ஐ திறக்கவும்.")
    fun deleteGuide() = t("For safety I don't delete or cancel records by voice. Open the item and use the menu there.",
        "பாதுகாப்புக்காக குரல் மூலம் பதிவுகளை நீக்க முடியாது. அந்த பதிவைத் திறந்து மெனுவைப் பயன்படுத்தவும்.")
    fun help() = t(
        "You can tell me what happened, for example:\n• Add 100 pieces of Blue Shirt\n• Received 50 boxes of A4 paper from Kumar Traders at 250 rupees each\n• Sold 20 Blue Shirts to Kumar Traders\n• Remove 10 Blue Shirts because they were damaged\n• Paid 2500 rupees for transport\n• How many Blue Shirts do I have?\n• Show low stock products\n• What were today's sales?\n• How much does Kumar Traders owe me?",
        "நடந்ததைச் சொல்லுங்கள். உதாரணம்:\n• Blue Shirt 100 pieces add pannunga\n• Kumar Traders kitta irundhu 50 box A4 paper 250 rupees-ku vandhuchu\n• Kumar Traders-ku 20 Blue Shirt vithen\n• 10 Blue Shirt damage, remove pannunga\n• Transport-ku 2500 rupees selavu\n• Blue Shirt evlo irukku?\n• Low stock products kaattu\n• இன்றைய விற்பனை எவ்வளவு?\n• Kumar Traders எவ்வளவு தர வேண்டும்?"
    )

    // clarification
    fun whichProduct(qty: String?) = if (qty != null) t("Which product should I use for $qty?", "$qty - எந்த பொருள்?")
        else t("Which product do you mean?", "எந்த பொருள்?")
    fun needProductFirst() = t("I need the product name first.", "முதலில் பொருளின் பெயர் தேவை.")
    fun howMany(name: String) = t("How many $name?", "$name - எத்தனை?")
    fun needNumber() = t("Please tell me a number.", "ஒரு எண்ணைச் சொல்லுங்கள்.")
    fun whatPrice(name: String) = t("What is the price per unit for $name?", "$name - ஒரு யூனிட் விலை என்ன?")
    fun whatAmount() = t("How much was it?", "எவ்வளவு தொகை?")
    fun whatFor() = t("What was the expense for?", "எதற்கான செலவு?")
    fun whatName() = t("What is the name?", "பெயர் என்ன?")
    fun whatToChange(name: String) = t("What should I change for $name? Say a new price, for example \"price 550\".", "$name-க்கு எதை மாற்ற வேண்டும்? உதாரணம்: \"price 550\".")
    fun recordType(name: String) = t("Should I add $name as a customer, a supplier or a product?", "$name-ஐ வாடிக்கையாளராக, சப்ளையராக அல்லது பொருளாக சேர்க்கவா?")
    fun didYouMean(name: String) = t("Did you mean $name?", "$name-ஐ சொல்கிறீர்களா?")
    fun multiple(count: Int, name: String) = t("I found $count products matching $name. Which one do you mean?", "$name-க்கு $count பொருட்கள் உள்ளன. எது?")
    fun multipleParties(count: Int, name: String) = t("I found $count names matching $name. Which one do you mean?", "$name-க்கு $count பெயர்கள் உள்ளன. எது?")
    fun pickOne() = t("Please choose one from the list, or say cancel.", "பட்டியலில் ஒன்றைத் தேர்ந்தெடுக்கவும், அல்லது cancel சொல்லவும்.")
    fun lowConfidence(what: String) = t("I think you want to: $what. Is that right?", "நீங்கள் சொல்வது: $what. சரியா?")

    // errors
    fun productNotFound(name: String) = t("I couldn't find '$name'. Please add it first or choose an existing product.", "'$name' கிடைக்கவில்லை. முதலில் அதைச் சேர்க்கவும் அல்லது உள்ள பொருளைத் தேர்ந்தெடுக்கவும்.")
    fun productExists(name: String) = t("$name already exists. Say \"add 10 $name\" to add stock.", "$name ஏற்கனவே உள்ளது. ஸ்டாக் சேர்க்க \"add 10 $name\" என்று சொல்லுங்கள்.")
    fun partyExists(name: String) = t("$name is already saved.", "$name ஏற்கனவே சேமிக்கப்பட்டுள்ளது.")
    fun partyNotFound(name: String) = t("I couldn't find '$name'.", "'$name' கிடைக்கவில்லை.")
    fun insufficient(name: String, have: String, want: String) = t("Not enough stock. $name has only $have, but you asked for $want. Nothing was changed.", "போதுமான ஸ்டாக் இல்லை. $name-ல் $have மட்டுமே உள்ளது, நீங்கள் கேட்டது $want. எதுவும் மாற்றவில்லை.")
    fun unitMismatch(name: String, unit: String, said: String) = t("$name is kept in $unit, not $said. Please say the quantity in $unit.", "$name $unit-ல் உள்ளது, $said அல்ல. $unit-ல் அளவைச் சொல்லுங்கள்.")
    fun noChange(name: String, qty: String) = t("$name is already at $qty. Nothing to change.", "$name ஏற்கனவே $qty-ல் உள்ளது. மாற்ற எதுவும் இல்லை.")

    // confirmation
    fun confirmTitle(intent: Intent) = when (intent) {
        Intent.ADD_PRODUCT -> t("Add product", "பொருள் சேர்")
        Intent.UPDATE_PRODUCT -> t("Update product", "பொருளை மாற்று")
        Intent.STOCK_IN -> t("Stock in", "ஸ்டாக் உள்ளே")
        Intent.STOCK_OUT -> t("Stock out", "ஸ்டாக் வெளியே")
        Intent.STOCK_ADJUST -> t("Stock adjustment", "ஸ்டாக் திருத்தம்")
        Intent.CREATE_SALE -> t("Sale", "விற்பனை")
        Intent.CREATE_PURCHASE -> t("Purchase", "கொள்முதல்")
        Intent.CREATE_CUSTOMER -> t("New customer", "புதிய வாடிக்கையாளர்")
        Intent.CREATE_SUPPLIER -> t("New supplier", "புதிய சப்ளையர்")
        Intent.CREATE_EXPENSE -> t("Expense", "செலவு")
        else -> ""
    }
    val lProduct get() = t("Product", "பொருள்")
    val lNewProduct get() = t("New product", "புதிய பொருள்")
    val lQuantity get() = t("Quantity", "அளவு")
    val lUnit get() = t("Unit", "யூனிட்")
    val lSupplier get() = t("Supplier", "சப்ளையர்")
    val lNewSupplier get() = t("New supplier", "புதிய சப்ளையர்")
    val lCustomer get() = t("Customer", "வாடிக்கையாளர்")
    val lNewCustomer get() = t("New customer", "புதிய வாடிக்கையாளர்")
    val lRate get() = t("Rate", "விலை")
    val lTotal get() = t("Total", "மொத்தம்")
    val lPayment get() = t("Payment", "பணம்")
    val lReason get() = t("Reason", "காரணம்")
    val lCurrent get() = t("Current stock", "தற்போதைய ஸ்டாக்")
    val lNew get() = t("New stock", "புதிய ஸ்டாக்")
    val lAdjustment get() = t("Adjustment", "திருத்தம்")
    val lAfter get() = t("Stock after", "பின் ஸ்டாக்")
    val lName get() = t("Name", "பெயர்")
    val lPhone get() = t("Phone", "தொலைபேசி")
    val lCategory get() = t("Category", "வகை")
    val lAmount get() = t("Amount", "தொகை")
    val lSelling get() = t("Selling price", "விற்பனை விலை")
    val lCost get() = t("Purchase price", "கொள்முதல் விலை")
    val lMin get() = t("Minimum stock", "குறைந்தபட்ச ஸ்டாக்")
    val lOpening get() = t("Opening stock", "தொடக்க ஸ்டாக்")
    val paid get() = t("Paid", "செலுத்தப்பட்டது")
    val credit get() = t("Credit (unpaid)", "கடன் (செலுத்தவில்லை)")
    val cashSale get() = t("Cash sale", "ரொக்க விற்பனை")
    fun reason(r: OutReason) = when (r) {
        OutReason.DAMAGE -> t("Damaged", "சேதம்")
        OutReason.RETURN_OUT -> t("Returned to supplier", "சப்ளையருக்கு திருப்பி அனுப்பப்பட்டது")
        OutReason.OTHER -> t("Removed", "நீக்கப்பட்டது")
    }
    val returnIn get() = t("Customer return", "வாடிக்கையாளர் திருப்பம்")

    fun askAddNew(qty: String, name: String) = t("$name is a new product. Create it with $qty in stock?", "$name புதிய பொருள். $qty ஸ்டாக்குடன் உருவாக்கவா?")
    fun askStockIn(qty: String, name: String) = t("$qty of $name. Add to stock?", "$name $qty. ஸ்டாக்கில் சேர்க்கவா?")
    fun askPurchase(qty: String, name: String, supplier: String, total: String) = t("Purchase $qty of $name from $supplier for $total. Save?", "$supplier-இடம் $name $qty, $total. சேமிக்கவா?")
    fun askStockOut(qty: String, name: String) = t("Remove $qty of $name from stock?", "$name $qty ஸ்டாக்கிலிருந்து நீக்கவா?")
    fun askAdjust(name: String, from: String, to: String) = t("Change $name stock from $from to $to?", "$name ஸ்டாக்கை $from-லிருந்து $to ஆக மாற்றவா?")
    fun askSale(qty: String, name: String, customer: String?, total: String) =
        if (customer != null) t("Sell $qty of $name to $customer for $total?", "$customer-க்கு $name $qty, $total. விற்கவா?")
        else t("Sell $qty of $name for $total?", "$name $qty, $total. விற்கவா?")
    fun askParty(kind: PartyKind, name: String) = if (kind == PartyKind.CUSTOMER) t("Add $name as a customer?", "$name-ஐ வாடிக்கையாளராக சேர்க்கவா?")
        else t("Add $name as a supplier?", "$name-ஐ சப்ளையராக சேர்க்கவா?")
    fun askExpense(category: String, amount: String) = t("Record $amount expense for $category?", "$category-க்கு $amount செலவு பதிவு செய்யவா?")
    fun askAddProduct(name: String) = t("Create product $name?", "$name பொருளை உருவாக்கவா?")
    fun askUpdate(name: String) = t("Update $name?", "$name-ஐ மாற்றவா?")

    // done
    fun doneStockIn(qty: String, name: String, after: String) = t("$qty of $name added to stock. You now have $after.", "$name $qty ஸ்டாக்கில் சேர்க்கப்பட்டது. இப்போது $after உள்ளது.")
    fun doneProductCreated(name: String, qty: String) = t("$name created with $qty in stock.", "$name உருவாக்கப்பட்டது. ஸ்டாக்: $qty.")
    fun doneStockOut(qty: String, name: String, after: String) = t("$qty of $name removed. You now have $after.", "$name $qty நீக்கப்பட்டது. இப்போது $after உள்ளது.")
    fun doneAdjust(name: String, after: String) = t("$name stock is now $after.", "$name ஸ்டாக் இப்போது $after.")
    fun doneSale(qty: String, name: String, total: String, after: String) = t("Sale saved: $qty of $name for $total. $after left in stock.", "விற்பனை சேமிக்கப்பட்டது: $name $qty, $total. மீதம் $after.")
    fun donePurchase(qty: String, name: String, total: String, after: String) = t("Purchase saved: $qty of $name for $total. You now have $after.", "கொள்முதல் சேமிக்கப்பட்டது: $name $qty, $total. இப்போது $after உள்ளது.")
    fun doneParty(kind: PartyKind, name: String) = if (kind == PartyKind.CUSTOMER) t("$name added as a customer.", "$name வாடிக்கையாளராக சேர்க்கப்பட்டது.")
        else t("$name added as a supplier.", "$name சப்ளையராக சேர்க்கப்பட்டது.")
    fun doneExpense(category: String, amount: String) = t("$amount expense saved for $category.", "$category-க்கு $amount செலவு சேமிக்கப்பட்டது.")
    fun doneUpdate(name: String) = t("$name updated.", "$name மாற்றப்பட்டது.")
    fun undone() = t("Undone. The change has been reversed.", "திரும்பப் பெறப்பட்டது.")
    fun undoFailed() = t("I couldn't undo that. Your data was not changed.", "அதைத் திரும்பப் பெற முடியவில்லை. தரவு மாற்றப்படவில்லை.")

    // answers
    fun stockOf(name: String, qty: String, low: Boolean) = t("You have $qty of $name.", "$name: $qty உள்ளது.") + if (low) t(" This is low stock.", " இது குறைந்த ஸ்டாக்.") else ""
    fun noProducts() = t("You have no products yet.", "இன்னும் பொருட்கள் இல்லை.")
    fun noLow() = t("No products are low on stock.", "எந்த பொருளும் குறைவாக இல்லை.")
    fun lowHeader(n: Int) = if (n == 1) t("1 product is low on stock:", "1 பொருள் குறைவாக உள்ளது:") else t("$n products are low on stock:", "$n பொருட்கள் குறைவாக உள்ளன:")
    fun lowLine(name: String, qty: String, min: String) = t("• $name: $qty (minimum $min)", "• $name: $qty (குறைந்தபட்சம் $min)")
    fun matchesHeader(name: String) = t("Products matching $name:", "$name-க்கு பொருந்தும் பொருட்கள்:")
    fun salesNone(period: String) = t("No sales $period.", "$period விற்பனை இல்லை.")
    fun salesSummary(period: String, count: Int, total: String, unpaid: String?) =
        t("$period: $count ${if (count == 1) "sale" else "sales"}, total $total", "$period: $count விற்பனை, மொத்தம் $total") +
            (if (unpaid != null) t(" ($unpaid not yet received).", " ($unpaid இன்னும் வரவில்லை).") else ".")
    fun purchasesNone(period: String) = t("No purchases $period.", "$period கொள்முதல் இல்லை.")
    fun purchasesSummary(period: String, count: Int, total: String, unpaid: String?) =
        t("$period: $count ${if (count == 1) "purchase" else "purchases"}, total $total", "$period: $count கொள்முதல், மொத்தம் $total") +
            (if (unpaid != null) t(" ($unpaid not yet paid).", " ($unpaid இன்னும் செலுத்தவில்லை).") else ".")
    fun expensesNone(period: String) = t("No expenses $period.", "$period செலவு இல்லை.")
    fun expensesSummary(period: String, count: Int, total: String) = t("$period: $count ${if (count == 1) "expense" else "expenses"}, total $total.", "$period: $count செலவு, மொத்தம் $total.")
    fun stockReport(products: Int, value: String, low: Int, out: Int) = t(
        "You have $products ${if (products == 1) "product" else "products"}. Stock value is $value. $low low on stock, $out out of stock.",
        "$products பொருட்கள் உள்ளன. ஸ்டாக் மதிப்பு $value. $low குறைவு, $out இல்லை."
    )
    fun customerOwes(name: String, amount: String) = t("$name owes you $amount.", "$name உங்களுக்கு $amount தர வேண்டும்.")
    fun customerClear(name: String) = t("$name does not owe you anything.", "$name எதுவும் தர வேண்டியதில்லை.")
    fun customerAdvance(name: String, amount: String) = t("$name has paid $amount in advance.", "$name $amount முன்பணம் கொடுத்துள்ளார்.")
    fun youOwe(name: String, amount: String) = t("You owe $name $amount.", "நீங்கள் $name-க்கு $amount தர வேண்டும்.")
    fun youOweNothing(name: String) = t("You do not owe $name anything.", "$name-க்கு நீங்கள் எதுவும் தர வேண்டியதில்லை.")
    fun youPaidAdvance(name: String, amount: String) = t("You have paid $name $amount in advance.", "நீங்கள் $name-க்கு $amount முன்பணம் கொடுத்துள்ளீர்கள்.")
    fun totalReceivable(amount: String, n: Int) = t("Customers owe you $amount in total ($n with a balance).", "வாடிக்கையாளர்கள் மொத்தம் $amount தர வேண்டும் ($n பேர்).")
    fun totalPayable(amount: String, n: Int) = t("You owe suppliers $amount in total ($n with a balance).", "சப்ளையர்களுக்கு மொத்தம் $amount தர வேண்டும் ($n பேர்).")
    fun noCustomers() = t("You have no customers yet.", "இன்னும் வாடிக்கையாளர்கள் இல்லை.")
    fun noSuppliers() = t("You have no suppliers yet.", "இன்னும் சப்ளையர்கள் இல்லை.")
    fun customersHeader(n: Int) = t("You have $n ${if (n == 1) "customer" else "customers"}:", "$n வாடிக்கையாளர்கள்:")
    fun suppliersHeader(n: Int) = t("You have $n ${if (n == 1) "supplier" else "suppliers"}:", "$n சப்ளையர்கள்:")
    fun partyDetail(kind: PartyKind, name: String, phone: String, balance: String) =
        (if (kind == PartyKind.CUSTOMER) t("Customer: $name", "வாடிக்கையாளர்: $name") else t("Supplier: $name", "சப்ளையர்: $name")) +
            (if (phone.isNotBlank()) t(", phone $phone", ", தொலைபேசி $phone") else "") + t(", balance $balance.", ", நிலுவை $balance.")
    fun productDetail(name: String, qty: String, price: String) = t("Product: $name, stock $qty, selling price $price.", "பொருள்: $name, ஸ்டாக் $qty, விலை $price.")
    fun andMore(n: Int) = t("…and $n more.", "…மேலும் $n.")
    fun periodPhrase(label: String) = label
}
