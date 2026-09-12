# Self-Hosting Nextcloud on Android (Termux + Debian)

Run your own private Nextcloud server directly on your Android phone using Termux, `proot-distro` Debian, Apache, MariaDB, and Tailscale for a permanent access link.

---

## Prerequisites

- Android phone with [Termux](https://f-droid.org/en/packages/com.termux/) installed (F-Droid build recommended)
- Stable storage and battery (keep the phone plugged in during install)
- A Tailscale account (free) for permanent remote access

---

## Part 1: Full Clean Installation (First-Time Setup)

Copy and paste each command block one at a time.

### A. Base Termux Setup (outside Debian)

```bash
pkg update && pkg upgrade -y
pkg install proot-distro -y
pkg install openssh termux-api wget curl -y

# Grant storage access
termux-setup-storage

# Prevent Android from killing the session
termux-wake-lock

# Install and enter Debian
proot-distro install debian
proot-distro login debian
```

### B. Inside Debian: Install Server Stack

```bash
apt update && apt upgrade -y

DEBIAN_FRONTEND=noninteractive apt install -y \
  apache2 mariadb-server \
  libapache2-mod-php php-gd php-curl php-zip php-xml \
  php-mbstring php-intl php-mysql php-imagick php-apcu \
  wget unzip curl nano
```

Change Apache's port from 80 to 8080 (unprivileged, works on Termux):

```bash
sed -i 's/Listen 80/Listen 8080/' /etc/apache2/ports.conf
sed -i 's/:80>/:8080>/' /etc/apache2/sites-available/000-default.conf
```

Start the services:

```bash
service mariadb start
service apache2 start
```

### C. Create the Database

```bash
mariadb -u root
```

Inside the MariaDB shell, run (replace `nextcloud`, `ncuser`, and the password with your own values — keep them consistent):

```sql
CREATE DATABASE nextcloud CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE USER 'ncuser'@'localhost' IDENTIFIED BY 'your-strong-password';
GRANT ALL PRIVILEGES ON nextcloud.* TO 'ncuser'@'localhost';
FLUSH PRIVILEGES;
EXIT;
```

> ⚠️ Use the **same** database name, username, and password consistently — you'll need them again during the Nextcloud web setup.

### D. Download & Install Nextcloud

```bash
cd /var/www/html
wget https://download.nextcloud.com/server/releases/latest.zip
unzip latest.zip
chown -R www-data:www-data nextcloud
```

Find your phone's local IP address:

```bash
ifconfig
```

Then open in a browser on the same network:

```
http://YOUR-PHONE-IP:8080/nextcloud
```

### E. Web Setup Wizard

On the Nextcloud setup page, fill in:

| Field | Value |
|---|---|
| Admin username/password | Choose anything — this is separate from the database credentials |
| Database user | The DB username you created in step C |
| Database password | The DB password you created in step C |
| Database name | The DB name you created in step C |
| Database host | `localhost` (leave as-is) |

Click **Install** and wait for it to finish, then skip the recommended apps.

> ⚠️ Your phone's local IP can change if Wi-Fi disconnects and reconnects — this is what Part 2 solves.

---

## Part 2: Permanent Access with Tailscale (Recommended)

Tailscale gives your phone a **fixed private IP** that never changes, so your Nextcloud link keeps working even if your Wi-Fi IP changes — and it works from anywhere, not just your home network.

1. Install **Tailscale** from the Play Store / App Store (or [tailscale.com](https://tailscale.com))
2. Open the app and log in (Google, GitHub, Microsoft, etc. — free tier is fine)
3. Turn Tailscale **ON**
4. Your phone is assigned a permanent Tailscale IP, e.g. `100.x.x.x`
5. Any other device on your Tailscale network can now reach your server at:

```
http://YOUR-TAILSCALE-IP:8080/nextcloud
```

Use this address instead of your local phone IP from now on.

### Nextcloud Mobile/Desktop App

1. Install the official **Nextcloud** app (Play Store / App Store / F-Droid)
2. Enter your server address: `http://YOUR-TAILSCALE-IP:8080/nextcloud`
3. It will open your default browser to log in with your **admin** credentials
4. Tap **Grant access**, confirm with your admin password
5. Once you see the confirmation message, close the browser tab
6. You'll be redirected back into the app, fully connected

You can now delete the setup files from the Nextcloud folder if you'd like to tidy things up.

---

## Notes

- Keep `termux-wake-lock` active (or disable battery optimization for Termux) so the server doesn't get killed in the background.
- Database credentials (created in Termux/MariaDB) and admin credentials (created in the web wizard) are **separate** — don't mix them up.
- For a truly persistent setup, consider adding a boot script or a Termux widget to auto-start `mariadb` and `apache2` on launch.
