# 我的電視 My TV

Android phone + Android TV app that finds **free, publicly available** channels
(Taiwan, rugby/sports, English), checks every link on the device, and replaces broken links.

手機與 Android TV 皆可使用。只收錄免費、公開的頻道，並在裝置上自動檢查與修復連結。

- Channel source: [iptv-org](https://github.com/iptv-org/iptv) public playlists
- Auto check every 14 days, or press **🔄 Check & fix**
- Remote: ↑/↓ change channel, OK shows channel name. Phone: swipe up/down.

Signing: `signing/tvapp.jks` is protected by a long random password stored only in the
`KEY_PASS` repository secret.
