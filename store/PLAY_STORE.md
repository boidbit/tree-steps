# Putting Tree Steps on Google Play

Everything the Play Console asks for, in the order it asks. The listing text below is ready to paste.

## 1. One-time setup

1. **Signing secrets on GitHub.** In the repo, go to Settings → Secrets and variables → Actions → New repository secret, and add:

   | Name | Value |
   |---|---|
   | `TS_KEYSTORE_BASE64` | the whole contents of `keystore-base64.txt` (one long line) |
   | `TS_KEYSTORE_PASSWORD` | the contents of `password.txt` |

   Both files are in the `TreeSteps-signing` folder on the Desktop, **outside** the repo. `TS_KEY_ALIAS` (`upload`) and `TS_KEY_PASSWORD` (same as the keystore password) are optional. Once the secrets are added, run **Actions → Build APK → Run workflow** (or push anything). The release then has `tree-steps-0.1.N.apk` (signed) and `tree-steps-0.1.N.aab` (for Play).
2. **Back up the signing folder** (USB drive, password manager or an encrypted cloud drive). Never put it in the repo. If you lose it, Google can reset the upload key, but that takes days.
3. **Privacy policy page.** In the repo, go to Settings → Pages → Build and deployment → Source: *Deploy from a branch*, Branch: `main`, folder `/docs`, then Save. After a minute the policy is at **https://boidbit.github.io/tree-steps/privacy.html**.
4. **Developer account** at https://play.google.com/console (one-time fee of US$25, plus identity verification).

## 2. Create the app

- App name: **Tree Steps: Walk & Grow**
- Default language: English (United States) · App · Free
- When asked about app signing, keep **Play App Signing** with a Google-generated key (the default). The key in `TreeSteps-signing` becomes your *upload key*.

> Phones that install the GitHub APK can't update to the Play version (or the other way round) without uninstalling first, because Google signs Play installs with its own key. Use the GitHub APK for testing only.

## 3. Store listing

**Short description** (80 characters max):

> Grow a living tree with every step. Keep your streak and animals move in.

**Full description:**

> Every step you walk grows your tree. Start the day with a seedling, and by the time you reach your goal it's in full bloom.
>
> 🌳 A tree that lives with you
> • Six trees from around the world: Banyan, Oak, Sakura, Maple, Pine and Jacaranda
> • The sky follows the real sun and moon for your time zone: dawn, day, sunset and starry nights
> • Keep your goal streak going and animals move in: butterflies, birds and their nest, a squirrel, a rabbit, even a peacock
> • Sit still for too long and your tree gets thirsty. A short walk perks it up
>
> 📱 On your phone, your way (all optional)
> • Live wallpaper for your lock and home screen, gentle on battery
> • Home screen widgets in three sizes
> • Or just open the app
>
> 👣 Steps you can trust
> • Uses your phone's step counter
> • Leaves out the shaking in cars, buses and trains
> • Or read your steps from Samsung Health or Google Fit through Health Connect
>
> 📊 Your journey
> • Distance, calories and walking minutes
> • A seven-day chart, an hour-by-hour view and your goal streak
> • My forest: one tree for every day you walk. Share any of them as a picture
> • Achievements to unlock
>
> 🔒 Private by design
> No account, no ads, no tracking. Your steps never leave your phone.

**Graphics** (all in this `store/` folder):

| Play Console field | File |
|---|---|
| App icon (512×512) | `icon-512.png` |
| Feature graphic (1024×500) | `feature-graphic-1024x500.png` |
| Phone screenshots (2–8, 1080×1920) | `screenshots/1-home.png` … `8-wallpaper-dawn.png` |

- **Category:** Health & Fitness · **Tags:** Walking, Step counter, Habit tracker
- **Contact email:** your email (Play shows it on the listing)
- **Website:** https://boidbit.github.io/tree-steps/
- **Privacy policy:** https://boidbit.github.io/tree-steps/privacy.html

## 4. App content (Policy → App content)

| Section | Answer |
|---|---|
| Privacy policy | https://boidbit.github.io/tree-steps/privacy.html |
| Ads | No, the app has no ads |
| App access | All functionality is available without special access (no login) |
| Content rating | Questionnaire, category *Utility, Productivity, Communication or Other*. Answer **No** to everything (no violence, user content, gambling, location sharing or purchases). The result is Everyone / PEGI 3 |
| Target audience | 13–15, 16–17 and 18+ (not under 13, which avoids the Families programme) |
| News app | No |
| Government app | No |
| Financial features | None |
| Health apps declaration | Yes, a health app. Feature: **Activity and fitness** (step tracking). Not a medical device |
| Data safety | See below |
| Health Connect permissions | `READ_STEPS`. Reason: *"Shows the user's daily steps and grows their tree, and fills in past days of history. Used only on the device, never shared or used for ads."* Link the privacy policy. Google reviews this. If it's refused, Health Connect can be removed and the app still works with the phone's step counter |

### Data safety answers

- **Does your app collect or share any of the required user data types?** **No.** All data (steps, height, weight, settings) is processed and stored only on the device, and nothing is sent off the device. Under Play's definitions, data processed only on the device doesn't count as "collected".
- If the form asks anyway: *Is all user data encrypted in transit?* Not applicable (no data is transmitted). *Can users request deletion?* Yes: clear the app's storage or uninstall.
- Sensitive permissions in the app: Physical activity (`ACTIVITY_RECOGNITION`), Health Connect steps (`READ_STEPS`) and notifications (`POST_NOTIFICATIONS`). All of them are explained in the privacy policy.

## 5. Testing, then production

New personal developer accounts must run a **closed test with at least 12 testers for 14 days in a row** before they can apply for production.

1. Testing → **Closed testing** → Create track → upload `tree-steps-0.1.N.aab` from the latest GitHub release → add testers by email (a Google Group works well) → roll out.
2. Testers join through the opt-in link and keep the app installed for 14 days.
3. Then go to Dashboard → **Apply for production**, answer the questions about the test, and once approved, create a production release with the latest `.aab`.

Each upload needs a higher version code. CI uses the build number, so a new GitHub build is always higher.

## 6. Before each release, check on a phone

- First launch: onboarding with the three choices (wallpaper, widget, just the app)
- Step counting after allowing "Physical activity"
- Adding the wallpaper and a widget of each size
- Evening reminder (set it to the next hour)
- Settings → Step source → Health Connect
- A car or bus ride with "Ignore steps in vehicles" on
