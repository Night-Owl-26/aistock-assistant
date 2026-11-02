package com.aistock.assistant.excel

import com.aistock.assistant.utils.MiniJson
import com.aistock.assistant.utils.NameMatcher

/**
 * Works out a product's category when the owner's file has no category column (or leaves it blank).
 * The AI groups the names when it is reachable; these keyword rules are used otherwise, and for any name the AI
 * left out. A category is only ever filled in where none was set; the owner's own categories are never replaced.
 */
object CategoryGuess {
    /** Category -> words that show a product belongs to it (checked against whole words of the name). */
    private val RULES: List<Pair<String, List<String>>> = listOf(
        "Innerwear" to listOf("brief", "briefs", "trunk", "trunks", "vest", "vests", "banian", "baniyan", "bra", "panty", "panties", "innerwear", "underwear", "slip", "camisole", "boxer", "boxers", "lingerie"),
        "Kids Wear" to listOf("kids", "kid", "baby", "babies", "infant", "frock", "romper", "toddler", "boys", "girls", "children", "child"),
        "T-Shirts" to listOf("tshirt", "tshirts", "t shirt", "t shirts", "tee", "tees", "polo", "round neck", "v neck", "crew neck"),
        "Shirts" to listOf("shirt", "shirts", "formal shirt", "casual shirt"),
        "Pants & Jeans" to listOf("pant", "pants", "trouser", "trousers", "jeans", "jean", "denim", "chinos", "chino", "cargo", "track pant", "trackpant", "joggers", "jogger", "shorts", "bermuda", "capri", "leggings", "legging", "jeggings"),
        "Sarees" to listOf("saree", "sarees", "sari", "silk saree", "cotton saree", "pattu"),
        "Churidar & Kurtis" to listOf("churidar", "chudidar", "kurti", "kurtis", "kurta", "kurtas", "salwar", "nighty", "nightie", "nightwear", "dupatta", "shawl", "top", "tops", "tunic", "gown", "lehenga"),
        "Dhotis & Lungis" to listOf("dhoti", "dhotis", "veshti", "vesti", "lungi", "lungis", "towel", "towels", "handkerchief", "kerchief"),
        "Winter Wear" to listOf("jacket", "jackets", "sweater", "sweaters", "hoodie", "hoodies", "sweatshirt", "thermal", "thermals", "blazer", "coat"),
        "Socks & Accessories" to listOf("sock", "socks", "cap", "caps", "belt", "belts", "tie", "ties", "scarf", "glove", "gloves", "wallet", "mask"),
        "Footwear" to listOf("shoe", "shoes", "slipper", "slippers", "sandal", "sandals", "chappal", "chappals", "sneaker", "sneakers", "flip flop", "boots", "boot", "heels"),
        "Fabric" to listOf("fabric", "cloth", "yarn", "thread", "cotton fabric", "lycra", "polyester", "rayon", "knit", "knitted", "dyed", "grey fabric", "interlock", "rib", "jersey", "fleece", "poplin", "twill", "elastic", "button", "buttons", "zip", "zipper", "label", "labels", "lace"),
        "Cleaning" to listOf("detergent", "washing powder", "surf", "dishwash", "phenyl", "cleaner", "bleach", "mop", "broom", "scrubber", "liquid soap", "harpic", "lizol", "vim"),
        "Personal Care" to listOf("soap", "shampoo", "conditioner", "toothpaste", "toothbrush", "brush", "cream", "lotion", "talcum powder", "face powder", "talc", "face wash", "facewash", "deodorant", "perfume", "hair oil", "comb", "razor", "sanitizer", "lipstick", "kajal", "cosmetic", "cosmetics", "sanitary", "napkin", "napkins", "pads", "diaper", "diapers"),
        "Rice & Grains" to listOf("rice", "atta", "maida", "rava", "sooji", "wheat", "ragi", "millet", "poha", "aval", "oats", "flour"),
        "Pulses & Dals" to listOf("dal", "dhal", "paruppu", "toor", "moong", "urad", "chana", "bengal gram", "green gram", "rajma", "peas", "lentil", "lentils"),
        "Oil & Ghee" to listOf("oil", "ghee", "vanaspati", "gingelly", "groundnut oil", "sunflower oil", "coconut oil"),
        "Spices & Masala" to listOf("masala", "chilli", "chili", "turmeric", "pepper", "jeera", "cumin", "mustard", "coriander", "cardamom", "clove", "cloves", "cinnamon", "spice", "spices", "salt", "hing", "asafoetida", "tamarind"),
        "Sugar & Beverages" to listOf("sugar", "jaggery", "tea", "coffee", "horlicks", "boost", "bournvita", "juice", "drink", "soda", "water bottle", "milk", "curd", "paneer", "butter", "cheese"),
        "Snacks & Biscuits" to listOf("biscuit", "biscuits", "cookie", "cookies", "chips", "namkeen", "mixture", "murukku", "snack", "snacks", "chocolate", "chocolates", "candy", "noodles", "maggi", "pasta", "cake", "bread", "rusk"),
        "Stationery" to listOf("pen", "pens", "pencil", "pencils", "notebook", "notebooks", "note book", "book", "books", "eraser", "sharpener", "scale", "marker", "markers", "stapler", "glue", "paper", "file", "folder", "crayons", "crayon"),
        "Mobile & Accessories" to listOf("mobile", "phone", "charger", "chargers", "cable", "cables", "earphone", "earphones", "headphone", "headphones", "earbuds", "power bank", "powerbank", "tempered", "screen guard", "back cover", "cover", "memory card", "pendrive", "sim"),
        "Electricals" to listOf("bulb", "bulbs", "led", "tube light", "tubelight", "switch", "switches", "socket", "wire", "wires", "fan", "fans", "mcb", "plug", "extension", "battery", "batteries", "torch", "inverter", "holder"),
        "Hardware & Tools" to listOf("screw", "screws", "nail", "nails", "bolt", "bolts", "nut", "nuts", "hammer", "spanner", "plier", "pliers", "drill", "pipe", "pipes", "tap", "valve", "paint", "cement", "lock", "locks", "hinge", "hinges", "tape", "adhesive"),
        "Medicines" to listOf("tablet", "tablets", "capsule", "capsules", "syrup", "ointment", "injection", "medicine", "bandage", "eye drops"),
        "Fruits & Vegetables" to listOf("onion", "tomato", "potato", "carrot", "beans", "brinjal", "cabbage", "banana", "apple", "orange", "mango", "grapes", "lemon", "vegetable", "vegetables", "fruit", "fruits", "garlic", "ginger"),
        "Kitchenware" to listOf("vessel", "vessels", "plate", "plates", "tumbler", "glass", "bowl", "kadai", "tawa", "pressure cooker", "cooker", "spoon", "spoons", "ladle", "container", "bottle", "flask", "lunch box", "knife", "pan"),
    )

    /** The category for a product name by the keyword rules, or null when no rule fits. */
    fun of(name: String): String? {
        val words = NameMatcher.tokens(name.replace('-', ' '))
        if (words.isEmpty()) return null
        val text = " " + words.joinToString(" ") + " "
        // Earlier rules win ("kids t-shirt" is Kids Wear, "cotton brief" is Innerwear), longer phrases before single words.
        for ((category, keys) in RULES) for (k in keys.sortedByDescending { it.length }) {
            val kw = NameMatcher.tokens(k.replace('-', ' '))
            if (kw.isNotEmpty() && text.contains(" " + kw.joinToString(" ") + " ")) return category
        }
        return null
    }

    /** The question for the AI: group these product names into a few short categories. */
    fun prompt(names: List<String>, existing: List<String>): String = buildString {
        append("These are product names from a small shop's stock file in Tamil Nadu. Put each product in a short category ")
        append("(1 to 3 words, in English, Title Case, plural like \"Shirts\", \"Sarees\", \"Rice & Grains\"). Use as few categories as make sense, ")
        append("the same category for similar products, and never a brand name or a size as the category.")
        if (existing.isNotEmpty()) append(" Prefer these categories the shop already uses when they fit: ").append(existing.take(40).joinToString(", ")).append('.')
        append(" Reply with only a JSON object mapping each product name exactly as written to its category, nothing else.\n\nProducts:\n")
        for (n in names) append("- ").append(n.take(80)).append('\n')
    }

    /**
     * The AI's answer, checked: only names that were asked about, categories of sensible length.
     * @return product key ([NameMatcher.key]) -> category
     */
    fun parse(reply: String?, names: List<String>): Map<String, String> {
        if (reply.isNullOrBlank()) return emptyMap()
        val start = reply.indexOf('{'); val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyMap()
        val o = try { MiniJson.parse(reply.substring(start, end + 1)) as? Map<*, *> } catch (e: Exception) { null } ?: return emptyMap()
        val asked = names.associateBy { NameMatcher.key(it) }
        val out = LinkedHashMap<String, String>()
        for ((k, v) in o) {
            val key = NameMatcher.key(k?.toString() ?: continue)
            if (key !in asked) continue
            val cat = v?.toString()?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.length in 2..40 && it.any(Char::isLetter) } ?: continue
            out[key] = NameMatcher.titleCase(cat)
        }
        return out
    }
}
