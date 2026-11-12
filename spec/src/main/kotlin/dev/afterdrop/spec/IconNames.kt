package dev.afterdrop.spec

/**
 * The icons a widget can name. They are Material icons, bundled with the app.
 * Generated from the icon set; an unknown name draws a small dot instead of failing.
 */
object IconNames {
    val ALL: Set<String> = setOf(
        "ac_unit", "account_balance", "add", "air", "alarm", "alarm_on", "arrow_back", "arrow_downward",
        "arrow_forward", "arrow_upward", "attach_money", "auto_stories", "bar_chart", "battery_alert", "battery_charging_full", "battery_full",
        "battery_saver", "battery_std", "beach_access", "bed", "bedtime", "bluetooth", "bolt", "book",
        "bookmark", "bug_report", "build", "cake", "calculate", "calendar_today", "call", "cancel",
        "card_giftcard", "celebration", "chair", "chat", "check", "check_circle", "checklist", "chevron_right",
        "church", "circle", "cleaning_services", "close", "cloud", "cloud_done", "cloud_off", "code",
        "coffee", "commute", "computer", "construction", "credit_card", "dark_mode", "date_range", "delete",
        "directions_bike", "directions_bus", "directions_car", "directions_run", "directions_walk", "do_not_disturb_on", "done", "download",
        "edit", "edit_note", "electric_bike", "emoji_events", "error", "euro", "ev_station", "event",
        "expand_more", "explore", "favorite", "fitness_center", "flag", "flight", "flood", "forest",
        "functions", "grain", "group", "handyman", "headphones", "headset", "help", "hiking",
        "history", "home", "hotel", "hourglass_empty", "image", "info", "key", "keyboard",
        "kitchen", "label", "language", "light_mode", "lightbulb", "link", "list", "local_cafe",
        "local_drink", "local_fire_department", "local_gas_station", "local_parking", "local_shipping", "location_on", "lock", "luggage",
        "mail", "map", "medication", "memory", "menu_book", "mic", "monitor_heart", "monitor_weight",
        "mosque", "movie", "music_note", "my_location", "near_me", "newspaper", "nights_stay", "note",
        "notifications", "notifications_active", "opacity", "open_in_new", "park", "pause", "payments", "pedal_bike",
        "percent", "person", "pets", "phone_android", "photo_camera", "pie_chart", "place", "play_arrow",
        "pool", "power", "print", "psychology", "public", "push_pin", "radio_button_unchecked", "recycling",
        "refresh", "remove", "restaurant", "rocket_launch", "rss_feed", "sailing", "savings", "schedule",
        "school", "science", "sd_storage", "search", "self_improvement", "sell", "settings", "severe_cold",
        "share", "shopping_cart", "show_chart", "shower", "signal_cellular_alt", "skip_next", "skip_previous", "snooze",
        "spa", "speed", "sports_esports", "sports_soccer", "star", "sticky_note_2", "storage", "subway",
        "sync", "task_alt", "terminal", "thermostat", "thunderstorm", "timelapse", "timer", "today",
        "tornado", "traffic", "train", "tram", "translate", "trending_down", "trending_flat", "trending_up",
        "tsunami", "tv", "two_wheeler", "umbrella", "update", "upload", "visibility", "volume_up",
        "wallet", "warning", "watch", "water", "water_drop", "waves", "wb_sunny", "wb_twilight",
        "wifi", "wind_power", "work",
    )

    /** Common words and newer icon names, mapped to an icon that exists. */
    val ALIASES: Map<String, String> = mapOf(
        "battery" to "battery_full", "bike" to "directions_bike", "calendar" to "event", "car" to "directions_car",
        "clear_day" to "wb_sunny", "clock" to "schedule", "cloudy_snowing" to "ac_unit", "eco" to "spa",
        "email" to "mail", "foggy" to "cloud", "heart" to "favorite", "laundry" to "cleaning_services",
        "location" to "place", "minus" to "remove", "money" to "payments", "music" to "music_note",
        "news" to "newspaper", "partly_cloudy_day" to "cloud", "phone" to "call", "plus" to "add",
        "rain" to "umbrella", "rainy" to "umbrella", "run" to "directions_run", "snow" to "ac_unit",
        "steps" to "directions_walk", "sun" to "wb_sunny", "sunny" to "wb_sunny", "todo" to "checklist",
        "trash" to "delete", "walk" to "directions_walk", "water_full" to "water_drop", "weather" to "wb_sunny",
        "weight" to "monitor_weight", "wind" to "air",
    )

    /** The bundled icon for [name], or null when there is none. */
    fun resolve(name: String?): String? = when {
        name == null -> null
        name in ALL -> name
        else -> ALIASES[name]
    }
}
