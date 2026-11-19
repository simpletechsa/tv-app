# STSA TV

Android phone + Android TV app (v2.3) that finds **free, publicly available** channels
(Taiwan, rugby/sports, English), checks every link on the device, and replaces broken links.

手機與 Android TV 皆可使用。只收錄免費、公開的頻道，並在裝置上自動檢查與修復連結。

- Folders: ❤️ Favorites · 🇹🇼 Taiwan · 🏉 Rugby · ⚽ Sports · 📰 News · 🎬 Movies · 📺 Series · 🧸 Kids · 🌍 Documentary · 🎵 Music · 🍳 Lifestyle · 🎭 Entertainment
- Taiwan: iptv-org links plus official 24h YouTube lives (opens the YouTube app). 中天 is never added.
- VPN on: the check still runs but only updates rugby (marked with the VPN country); Taiwan and other channels keep their last no-VPN result.
- English channels from ZA, UK, US, AU, NZ, KR, JP only; no VPN channels except rugby (country shown)
- Opens instantly; daily link check runs silently in the background; TV guide from epgshare01; channel source: iptv-org
- Clean screen: folders + channels only; press the STSA TV title for Settings (version, last check, Check now, My playlists.
- Remote: ↑/↓ channel · OK see-through list (←/→ page, Back = folders) · hold OK = favorite · volume keys for volume. Phone: swipe, tap = list, long-press = favorite.

Signing: `signing/tvapp.jks` is protected by a long random password stored only in the
`KEY_PASS` repository secret.
