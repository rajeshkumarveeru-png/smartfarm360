export type Language = 'en' | 'ta';
export const translations = {
  en: {
    appName: 'SmartFarm360', greeting: 'Your farm, at a glance', subtitle: 'Integrated farm management', dashboard: 'Home', records: 'Entries', crops: 'Crops', livestock: 'Livestock', more: 'More',
    revenue: 'Income', expenses: 'Expenses', net: 'Net cash flow', quickActions: 'Quick entry', addExpense: 'Add expense', addIncome: 'Add income', recent: 'Recent entries', noEntries: 'No entries yet. Add your first farm transaction.',
    amount: 'Amount (₹)', description: 'Description', category: 'Category', date: 'Date (YYYY-MM-DD)', save: 'Save entry', cancel: 'Cancel', expense: 'Expense', income: 'Income', language: 'தமிழ்',
    cropOverview: 'Crop overview', cropHelp: 'Track fields, crop cycles, input costs and harvests.', land: 'Land parcels', activeCycles: 'Active crop cycles', planned: 'Planned activities', livestockOverview: 'Livestock overview', livestockHelp: 'Track animals, daily production, feed and health.', animals: 'Animals', production: 'Today’s production', feedCost: 'Feed cost', comingSoon: 'Detailed module coming in the next release',
    modules: 'Farm modules', settings: 'Settings', farmProfile: 'Farm profile', sync: 'Sync status', localMode: 'Local mode', apiConfigured: 'API URL configured', apiMissing: 'API not configured', safety: 'Your entries are saved on this device. Connect a backend before using across devices.',
    totalEntries: 'Total entries', month: 'This month', categories: 'Categories', seed: 'Seeds', fertilizer: 'Fertilizer', labour: 'Labour', feed: 'Animal feed', veterinary: 'Veterinary', irrigation: 'Irrigation', transport: 'Transport', other: 'Other', milk: 'Milk sales', cropSale: 'Crop sales', eggs: 'Egg sales', livestockSale: 'Livestock sales',
    required: 'Enter an amount and description.', saved: 'Entry saved on this device.', noFarmData: 'No farm records yet', profileDesc: 'Farm name, location, land area and preferred language can be configured when the farm setup module is connected.'
  },
  ta: {
    appName: 'ஸ்மார்ட் ஃபார்ம் 360', greeting: 'உங்கள் பண்ணை நிலவரம்', subtitle: 'ஒருங்கிணைந்த பண்ணை மேலாண்மை', dashboard: 'முகப்பு', records: 'பதிவுகள்', crops: 'பயிர்கள்', livestock: 'கால்நடை', more: 'மேலும்',
    revenue: 'வருமானம்', expenses: 'செலவுகள்', net: 'நிகர பண ஓட்டம்', quickActions: 'விரைவு பதிவு', addExpense: 'செலவு சேர்', addIncome: 'வருமானம் சேர்', recent: 'சமீபத்திய பதிவுகள்', noEntries: 'பதிவுகள் இல்லை. முதல் பண்ணை பரிவர்த்தனையைச் சேர்க்கவும்.',
    amount: 'தொகை (₹)', description: 'விவரம்', category: 'வகை', date: 'தேதி (YYYY-MM-DD)', save: 'பதிவைச் சேமி', cancel: 'ரத்து', expense: 'செலவு', income: 'வருமானம்', language: 'English',
    cropOverview: 'பயிர் நிலவரம்', cropHelp: 'நிலம், பயிர் சுழற்சி, இடுபொருள் செலவு மற்றும் அறுவடையைப் பதிவு செய்யவும்.', land: 'நிலப் பகுதிகள்', activeCycles: 'நடப்பு பயிர் சுழற்சிகள்', planned: 'திட்டமிட்ட பணிகள்', livestockOverview: 'கால்நடை நிலவரம்', livestockHelp: 'விலங்குகள், தினசரி உற்பத்தி, தீவனம் மற்றும் ஆரோக்கியத்தைப் பதிவு செய்யவும்.', animals: 'விலங்குகள்', production: 'இன்றைய உற்பத்தி', feedCost: 'தீவனச் செலவு', comingSoon: 'விரிவான தொகுதி அடுத்த வெளியீட்டில்',
    modules: 'பண்ணை தொகுதிகள்', settings: 'அமைப்புகள்', farmProfile: 'பண்ணை விவரம்', sync: 'ஒத்திசைவு நிலை', localMode: 'சாதனத்தில் மட்டும்', apiConfigured: 'API முகவரி அமைக்கப்பட்டுள்ளது', apiMissing: 'API அமைக்கப்படவில்லை', safety: 'உங்கள் பதிவுகள் இந்தச் சாதனத்தில் சேமிக்கப்படுகின்றன. பல சாதனங்களில் பயன்படுத்த backend இணைக்கவும்.',
    totalEntries: 'மொத்த பதிவுகள்', month: 'இந்த மாதம்', categories: 'வகைகள்', seed: 'விதைகள்', fertilizer: 'உரம்', labour: 'கூலி', feed: 'கால்நடை தீவனம்', veterinary: 'கால்நடை மருத்துவம்', irrigation: 'நீர்ப்பாசனம்', transport: 'போக்குவரத்து', other: 'மற்றவை', milk: 'பால் விற்பனை', cropSale: 'பயிர் விற்பனை', eggs: 'முட்டை விற்பனை', livestockSale: 'கால்நடை விற்பனை',
    required: 'தொகை மற்றும் விவரத்தை உள்ளிடவும்.', saved: 'பதிவு இந்தச் சாதனத்தில் சேமிக்கப்பட்டது.', noFarmData: 'பண்ணை பதிவுகள் இல்லை', profileDesc: 'பண்ணை அமைப்பு இணைக்கப்பட்டதும் பண்ணை பெயர், இடம், நில அளவு மற்றும் மொழியை அமைக்கலாம்.'
  }
} as const;
export type TranslationKey = keyof typeof translations.en;
