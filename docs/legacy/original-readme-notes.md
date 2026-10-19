> **Archived notes — not current documentation.** This was the repository README before Phase 1.
> It was inherited from the BizManager/SmartBill template and describes things this repository
> does not contain (Flyway, JWT, GST invoices, docker-compose). It is kept verbatim because it holds
> personal setup notes. See the root [README](../../README.md) for accurate instructions.
>
> **Do not run** the `Set-NetFirewallProfile ... -Enabled False` command below: it turns off the
> Windows firewall entirely. Use the scoped `New-NetFirewallRule` for port 8080 instead.

# BizManager Full Stack

A complete starter ERP/business management application with a React + TypeScript frontend and Java 21 + Spring Boot backend.

## Modules
- JWT login/authentication
- Dashboard
- Customers
- Vendors
- Products
- Purchases
- Inventory transactions
- Sales / Billing
- Payments
- Employees
- Attendance
- Reports
- GST invoice PDF generation

## Stack
### Backend
- Java 21
- Spring Boot 3.5.4
- Spring Security
- JWT (jjwt)
- Spring Data JPA
- PostgreSQL
- Flyway
- Gradle
- OpenPDF

### Frontend
- React 19
- TypeScript
- Vite
- React Router
- Axios
- Recharts
- Browser print/PDF support

## Quick start

### 1. Database
Install Docker Desktop, then from the project root:

```bash
docker compose up -d postgres
```

### 2. Backend

Windows:
```bash
cd backend
gradlew.bat bootRun
```

Linux/macOS:
```bash
cd backend
./gradlew bootRun
```

API: http://localhost:8080/api

Demo login:
- username: admin
- password: admin123

### 3. Frontend

```bash
cd frontend
npm install
npm install -D @types/react @types/react-dom
npm install -D vite
npm install react react-dom
npm install -D typescript @types/react @types/react-dom vite
npm install lucide-react @zxing/browser
npm run dev
```

Frontend: http://localhost:5173

The Vite proxy sends `/api` requests to the Spring Boot server.

## Production
Build the frontend:

```bash
npm run build
```

For AWS, the frontend can be hosted on S3 + CloudFront and the backend on EC2/ECS, with PostgreSQL on RDS. Update the API base URL through `VITE_API_BASE_URL`.

## Notes
This is a runnable ERP foundation designed to be extended with company-specific GST rules, role/permission matrices, audit trails, and production infrastructure settings.

### Pwershell need to exute
Invoke-RestMethod `
    -Uri "http://localhost:8080/api/auth/login" `
-Method POST `
    -ContentType "application/json" `
-Body '{"username":"admin","password":"YOUR_PASSWORD"}'

-- Test-NetConnection localhost -Port 5173

--- Test-NetConnection localhost -Port 8080


cd D:\source_code\BizManager-Full-Stack-Latest\BizManager-Full-Stack\backend Get-ChildItem -Recurse -Filter *.java | Select-String -Pattern '@RequestMapping\("/"\)','@RequestMapping\("/\*\*"\)','@GetMapping\("/"\)'

curl.exe -i http://localhost:8080/index.html

Get-Process java | Select-Object Id,Path

Rename-Item .\src\main\java\com\bizmanager\pdf\InvoicePdfService.java InvoicePdfService.java.disabled

Remove-Item .\src\main\java\com\bizmanager\config\SpaController.java

Remove-Item .\src\main\java\com\bizmanager\pdf\InvoicePdfService.java.disabled -ErrorAction SilentlyContinue


.\gradlew.bat clean bootJar

java -jar .\build\libs\biz360.jar

java -jar .\build\libs\biz360.jar --debug

Get-ChildItem -Recurse -File | Select-String -Pattern "server.servlet.context-path|spring.web.resources|spring.mvc|static-locations|add-mappings|context-path"

Get-ChildItem .\src\main\java -Recurse -Filter *.java | Select-String -Pattern '@RequestMapping|@GetMapping|@PostMapping|@PutMapping|@DeleteMapping'


## To chnage the DOmain name

1. Open the hosts file correctly

Press Start → type:

Notepad

Right-click Notepad → Run as administrator.

In Notepad:

File → Open

Go to:

C:\Windows\System32\drivers\etc

At the bottom, change:

Text Documents (*.txt)

to:

All Files (*.*)

Select:

hosts
2. Add this line

At the bottom of the file add:

127.0.0.1    www.mybiz360.com

For example:

# localhost
127.0.0.1    localhost

# Biz360 local domain
127.0.0.1    www.mybiz360.com

## Run All these in command promt

type C:\Windows\System32\drivers\etc\hosts

ipconfig /flushdns

ping www.mybiz360.com

nslookup www.mybiz360.com


# DataBase Info



& "C:\Program Files\PostgreSQL\18\bin\psql.exe" -U postgres -h localhost

& "C:\Program Files\PostgreSQL\18\bin\psql.exe" -U smartfarm360 -h localhost -d smartfarm360


\c postgres
\c smartfarm360
DROP DATABASE smartfarm360;

CREATE DATABASE smartfarm360;

\c smartfarm360

CREATE ROLE smartfarm360 WITH LOGIN PASSWORD 'smartfarm360';

ALTER SCHEMA public OWNER TO smartfarm360;

GRANT USAGE, CREATE ON SCHEMA public TO smartfarm360;

# mobile build

cd mobile
npm install
npx expo start

Then in PowerShell:

cd D:\source_code\BizManager-Full-Stack-Latest\BizManager-Full-Stack\mobile

Remove-Item -Recurse -Force node_modules -ErrorAction SilentlyContinue
Remove-Item package-lock.json -ErrorAction SilentlyContinue

cd D:\source_code\BizManager-Full-Stack-Latest\BizManager-Full-Stack\mobile

npm install

npx expo install --fix

npx expo-doctor

npx expo config --json


npm install -g eas-cli
eas --version
eas login
eas whoami
eas build:configure
npx expo-doctor
npx expo install expo-dev-client
eas build --platform android --profile development

npx expo start --dev-client

mkdir certs
winget install FiloSottile.mkcert
mkcert -version
mkcert -install
mkcert -key-file .\certs\192.168.1.9-key.pem -cert-file .\certs\192.168.1.9.pem 192.168.1.9 localhost 127.0.0.1

#### scanneer

npm install @zxing/browser

## Off the firewall

Get-NetFirewallProfile | Select-Object Name, Enabled

Set-NetFirewallProfile -Profile Domain,Public,Private -Enabled False

New-NetFirewallRule `
  -DisplayName "Biz360 Spring Boot 8080" `
-Direction Inbound `
  -Protocol TCP `
-LocalPort 8080 `
  -Action Allow `
-Profile Private

Get-NetFirewallRule -DisplayName "Biz360 Spring Boot 8080"

Set-NetConnectionProfile -InterfaceAlias "Wi-Fi" -NetworkCategory Private

Get-NetConnectionProfile

Get-NetFirewallRule -DisplayName "Biz360 Spring Boot 8080" |
Format-Table DisplayName,Enabled,Profile,Direction,Action

ipconfig

# Remove NPM catch

Remove-Item -Recurse -Force node_modules
Remove-Item -Recurse -Force dist -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force .vite -ErrorAction SilentlyContinue
npm cache clean --force
npm install
npm run build
npm run dev

In Chrome:

Ctrl + Shift + R

or open DevTools → hold the reload button → Empty Cache and Hard Reload.

## Restore backup Dump
& "C:\Program Files\PostgreSQL\18\bin\pg_restore.exe" `
  -l `
"D:\Biz360\backups\bizmanager_2026-09-04.backup"

## To make as destop Application
cd D:\source_code\smartbilling
New-Item -ItemType Directory -Force -Path electron
cd D:\source_code\smartbilling\electron
need add below files manually
main.cjs
preload.cjs
npm install --save-dev electron electron-builder


Copy-Item .\build\libs\*.jar D:\source_code\BIZ360\desktop\backend\biz360.jar

# to .exe

npm run dist