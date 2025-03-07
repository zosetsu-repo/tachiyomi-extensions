| Build | Up to date | Install to app |
|-------|------------|----------------|
| [![Build](https://github.com/kana-shii/kanashii-extensions/actions/workflows/build_push.yml/badge.svg)](https://github.com/kana-shii/kanashii-extensions/actions/workflows/build_push.yml) | [![Updated](https://img.shields.io/github/actions/workflow/status/kana-shii/kanashii-extensions/auto_cherry_pick.yml?label=Updated&labelColor=27303D)](https://github.com/kana-shii/kanashii-extensions/actions/workflows/auto_cherry_pick.yml) | [![Install](https://img.shields.io/badge/Click%20here%20to%20install%20repo-gray?style=flat&labelColor=red)](https://intradeus.github.io/http-protocol-redirector/?r=tachiyomi://add-repo?url=https://raw.githubusercontent.com/kana-shii/extensions/repo/index.min.json) |

# Personal Repo
This repository contains the available extension catalogues is built-in with [Komikku](https://github.com/komikku-app/komikku) app while also usable for the Mihon / Tachiyomi app or other forks.

This repository automatically merges any updates from [Keiyoushi](https://github.com/keiyoushi/extensions-source) every 6 hours to have the best of community contributions. Beside from that, it has a few of my developed extensions or some improvements. Enjoy!

Some extensions from this repo provide better support for Komikku's feature showing related titles.

# Extensions with changes

- [x] Bato.To
- [x] Cubari
- [x] E-H
- [x] Hitomi
- [x] MangaPark
- [x] MyReadingManga
- [x] NH
- [x] YMO
- [x] Mangago
- [X] MangaPlanet

### [TachiyomiSY](https://github.com/jobobby04/TachiyomiSY) got all the original Tachiyomi app and more:
* TachiyomiSY will continue! It will basing the fork on a new Tachiyomi spiritual successor called Mihon.
* Cool migrate feature allow to migrate multiple mangas at once, very convenient if source's package changes or dropped.

### [Mihon](https://github.com/mihonapp/mihon) is said to be Tachiyomi's spiritual successor

### [Tachiyomi](https://github.com/tachiyomiorg/TachiyomiSY) the original

# How to add the repo
This repo include all previously existed source from Tachiyomi before the removal.

## One-click installation
One-click installation is only supported by these Tachiyomi versions:
* Komikku
* Tachiyomi v0.15.2+
* Tachiyomi Preview r6404+
* TachiyomiSY v1.10.0+
* TachiyomiSY Preview r539+
* Aniyomi Preview r7443+

Navigate to [the website](https://komikku-repo.github.io/) and tap "Add to Tachiyomi", then restart the app.

## TachiyomiAZ
1. Go to Settings → Browse
2. Tap on "Edit repos" and then "+" button at bottom
3. Input komikku-repo/extensions
4. Enjoy!

## Manually
External repositories add additional sources to **Komikku**. You can add external repositories by going to **More** -> **Settings** -> **Browse** and tapping **Extension repos**.

Once there, you can add repositories by inputting this URL: `https://raw.githubusercontent.com/komikku-repo/extensions/repo/index.min.json`

Once you've added a repository, go to Browse -> Extensions and refresh the extensions list.

You can now tap the download button next to extensions to install them.

> You may need to [enable third-party installations](https://komikku-app.github.io/docs/faq/browse/extensions#enabling-third-party-installations).

## Others
If you're not using any of the above fork then manually download and update extensions from the [listing page](https://komikku-repo.github.io/extensions/)


# Usage

[Getting started](https://komikku-repo.github.io/docs/guides/getting-started#adding-the-extension-repo)

Extension sources can be downloaded, installed, and uninstalled via the main Komikku app. They are installed and uninstalled like regular apps, in `.apk` format.

## Downloads

If you prefer to directly download the APK files, they are available via https://komikku-repo.github.io/extensions/ or directly in this GitHub repository in the [`repo` branch](https://github.com/komikku-repo/extensions/tree/repo/apk).

# Requests

To request a new source or bug fix, [create an issue](https://github.com/komikku-repo/komikku-extensions/issues/new/choose).

Please note that creating an issue does not mean that the source will be added or fixed in a timely
fashion, because the work is volunteer-based. Some sources may also be impossible to do or prohibitively
difficult to maintain.

If you would like to see a request fulfilled and have the necessary skills to do so, consider contributing!
Issues are up-for-grabs for any developer if there is no assigned user already.

# Contributing

Contributions are welcome!

Check out the repo's [issue backlog](https://github.com/komikku-repo/komikku-extensions/issues) for source requests and bug reports.

To get started with development, see [CONTRIBUTING.md](./CONTRIBUTING.md).

It might also be good to read our [CODE_OF_CONDUCT.md](./CODE_OF_CONDUCT.md).

## License

    Copyright 2015 Javier Tomás

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

## Disclaimer

This project does not have any affiliation with the content providers available.

This project is not affiliated with Mihon/Tachiyomi. Don't ask for help about these extensions at the
official support means of Mihon/Tachiyomi. All credits to the codebase goes to the original contributors.

The developer of this application does not have any affiliation with the content providers available.
