package com.reflex1337.reflexmirror

/**
 * AI Knowledge Base for Smart Curated Rows.
 * Maps Category Names to strict keywords/titles found in movie data.
 */
object SmartCuratedLists {
    
    val categories: Map<String, List<String>> = mapOf(
        // --- Basic Platforms (Added by request) ---
        // These are handled in the provider directly, but can be here if needed.
        
        // --- Franchises & Universes (Mapped with specific titles) ---
        "🧙‍♂️ Wizarding World (Harry Potter)" to listOf("harry potter", "fantastic beasts", "hogwarts", "dumbledore", "voldemort", "chamber of secrets", "prisoner of azkaban", "goblet of fire", "order of the phoenix", "half-blood prince", "deathly hallows"),
        "🕷️ Spider-Man Universe" to listOf("spider-man", "spiderman", "venom", "morbius", "madame web", "no way home", "far from home", "homecoming", "across the spider-verse", "into the spider-verse"),
        "🦇 Batman & Gotham" to listOf("batman", "dark knight", "joker", "gotham", "penguin", "begins", "rises", "arkham"),
        "🤠 Marvel Cinematic Universe" to listOf("avengers", "iron man", "captain america", "thor", "black panther", "doctor strange", "guardians of the galaxy", "wakanda", "hulk", "deadpool", "wolverine", "eternals", "shang-chi", "ant-man", "spider-man"),
        "🦸 DC Extended Universe" to listOf("superman", "wonder woman", "aquaman", "flash", "justice league", "green lantern", "shazam", "black adam", "man of steel", "batman v superman"),
        "⚔️ Middle Earth (LOTR)" to listOf("lord of the rings", "the hobbit", "gandalf", "aragorn", "bilbo", "fellowship of the ring", "two towers", "return of the king"),
        "🚀 Star Wars Galaxy" to listOf("star wars", "mandalorian", "skywalker", "boba fett", "ahsoka", "yoda", "jedi", "sith", "empire strikes back", "force awakens", "rogue one"),
        "🏎️ Fast & Furious" to listOf("fast and furious", "fast & furious", "toretto", "hobbs and shaw", "tokyo drift", "furious 7", "fate of the furious"),
        "🤖 Transformers" to listOf("transformers", "bumblebee", "optimus prime", "megatron", "age of extinction", "dark of the moon", "rise of the beasts"),
        "🦖 Jurassic World" to listOf("jurassic", "dinosaurs", "park", "world", "fallen kingdom", "dominion", "lost world"),
        "🏴‍☠️ Pirates of the Caribbean" to listOf("pirates of the caribbean", "jack sparrow", "captain jack", "black pearl", "dead man's chest", "at world's end", "on stranger tides"),
        "🔫 John Wick" to listOf("john wick", "baba yaga", "chapter 1", "chapter 2", "chapter 3", "chapter 4"),
        "🕵️ James Bond" to listOf("james bond", "007", "skyfall", "casino royale", "spectre", "no time to die", "quantum of solace", "goldeneye"),
        "🎬 Mission Impossible" to listOf("mission impossible", "ethan hunt", "fallout", "rogue nation", "ghost protocol"),

        // --- Horror & Thriller ---
        "🔪 Classic Slashers" to listOf("saw", "conjuring", "annabelle", "nun", "insidious", "friday the 13th", "nightmare on elm street", "halloween", "chucky", "leatherface", "texas chainsaw", "scream", "hellraiser"),
        "🦠 Zombies & Apocalypse" to listOf("zombie", "apocalypse", "resident evil", "walking dead", "world war z", "train to busan", "28 days later", "dawn of the dead"),
        "🧛 Vampires & Werewolves" to listOf("vampire", "werewolf", "dracula", "twilight", "underworld", "blade", "interview with the vampire"),
        "👻 Paranormal & Ghosts" to listOf("ghost", "paranormal", "haunting", "exorcist", "poltergeist", "demon", "amityville", "witch"),

        // --- Sci-Fi & Fantasy ---
        "⏳ Time Travel Adventures" to listOf("time travel", "time machine", "time loop", "back to the future", "terminator", "edge of tomorrow", "primer", "predestination"),
        "🛸 Alien & Space Invaders" to listOf("alien", "ufo", "extraterrestrial", "invasion", "predator", "arrival", "close encounters", "independence day"),
        "🤖 AI & Cyberpunk" to listOf("artificial intelligence", "cyberpunk", "hacker", "matrix", "cyborg", "blade runner", "ex machina", "terminator"),
        "🌍 Space Exploration" to listOf("space", "nasa", "astronaut", "interstellar", "gravity", "moon", "martian", "star trek", "apollo"),
        "🗡️ Fantasy Epics" to listOf("fantasy", "game of thrones", "witcher", "narnia", "elder scrolls", "dungeons & dragons", "warcraft"),

        // --- Action & Crime ---
        "💰 Heists & Robberies" to listOf("heist", "robbery", "thief", "bank rob", "money heist", "ocean's", "italian job", "baby driver", "inside man"),
        "🔫 Assassins & Hitmen" to listOf("assassin", "hitman", "contract killer", "john wick", "leon", "mechanic", "wanted"),
        "🥋 Martial Arts & Ninja" to listOf("martial arts", "kung fu", "karate", "ninja", "samurai", "ip man", "bruce lee", "jackie chan", "jet li"),
        "🕵️ Espionage & Spies" to listOf("spy", "espionage", "agent", "cia", "fbi", "mi6", "bourne", "tinker tailor"),

        // --- Drama & Themes ---
        "📈 Business, Money & Success" to listOf("business", "wall street", "money", "rich", "ceo", "company", "invest", "stock", "bank", "empire", "founder", "entrepreneur", "wolf of wall street", "social network"),
        "⚖️ Courtroom & Legal" to listOf("court", "lawyer", "attorney", "legal", "judge", "trial", "witness", "plead"),
        "🏥 Medical & Doctors" to listOf("doctor", "medical", "hospital", "surgeon", "nurse", "grey's anatomy", "house", "scrubs", "good doctor"),
        "🎓 College & University" to listOf("college", "university", "campus", "freshman", "professor", "dorm"),
        "🏫 High School & Teens" to listOf("high school", "teenage", "coming of age", "prom", "adolescent", "rebel"),
        "🎵 Music & Pop Stars" to listOf("music", "singer", "band", "rockstar", "rapper", "dj", "beatles", "queen", "bohemian rhapsody", "a star is born"),
        "💃 Dance & Performing" to listOf("dance", "dancer", "ballet", "hip hop", "step up", "black swan"),

        // --- Lifestyles & Cultures ---
        "🍜 Food & Culinary" to listOf("food", "chef", "culinary", "cooking", "restaurant", "ratatouille", "burnt", "hundred-foot journey"),
        "✈️ Travel & Adventure" to listOf("travel", "adventure", "journey", "expedition", "tour", "wild", "into the wild", "lost in translation"),
        "🌍 Culture & Lifestyle" to listOf("culture", "lifestyle", "tradition", "heritage", "festival", "wedding"),
        "🐾 Nature & Wildlife" to listOf("nature", "wildlife", "safari", "jungle", "forest", "animal", "lion", "tiger", "bear"),

        // --- Anime ---
        "🍃 Studio Ghibli Magic" to listOf("studio ghibli", "ghibli", "miyazaki", "spirited away", "my neighbor totoro", "howl's moving castle", "princess mononoke", "kiki's delivery service", "castle in the sky", "ponyo", "the wind rises", "arrietty", "tale of the princess kaguya"),
        "⚔️ Shonen Anime" to listOf("naruto", "one piece", "dragon ball", "bleach", "demon slayer", "my hero academia", "attack on titan", "jujutsu kaisen", "hunter x hunter", "fullmetal alchemist")
    )
}
