<#
  Demo data for local testing. Talks to the running backend.

  - Scans in 10 fake attendees, opens each page (like a phone, keeping its cookie)
    and saves a name + short intro. One intro hides a blocked word inside a longer word.
  - Scans in one more person WITHOUT opening the page, and prints their phone link,
    so you can see the first-visit form.
  - Moderation: flags one person once (red row), and flags another 3 times and lets
    them edit (PENDING_REVIEW), so the dashboard shows every row colour.

  Usage (from the project folder):
    powershell -ExecutionPolicy Bypass -File scripts\demo-data.ps1
    powershell -ExecutionPolicy Bypass -File scripts\demo-data.ps1 -Reset   # wipe the app first

  Options:
    -Password     admin password (asked for if not given; in dev it is printed at startup)
    -Reset        POST /api/admin/reset first, so the script can be run again
    -BaseUrl      backend address  (default http://localhost:8080)
    -FrontendUrl  Vue dev server   (default http://localhost:5173), only used for the printed link

  Restarting the backend (also DevTools' auto-restart on save) wipes the demo data.
#>
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$FrontendUrl = 'http://localhost:5173',
    [string]$Password,
    [switch]$Reset
)

$ErrorActionPreference = 'Stop'

if (-not $Password) {
    $secure = Read-Host 'Admin password (printed at startup: "Using generated security password")' -AsSecureString
    $Password = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
        [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
}
$adminHeaders = @{
    Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("admin:$Password"))
}

# One HTTP call. Sends JSON as UTF-8 (so accents survive), reads the answer as UTF-8,
# and turns HTTP errors into a readable message.
function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        $Body,
        [hashtable]$Headers = @{},
        $Session
    )
    $params = @{ Uri = "$BaseUrl$Path"; Method = $Method; Headers = $Headers; UseBasicParsing = $true }
    if ($null -ne $Body) {
        $params.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Compress))
        $params.ContentType = 'application/json; charset=utf-8'
    }
    if ($Session) { $params.WebSession = $Session }

    try {
        $response = Invoke-WebRequest @params
    } catch {
        $httpResponse = $_.Exception.Response
        if (-not $httpResponse) {
            throw "Can't reach $BaseUrl. Is the backend running? ($($_.Exception.Message))"
        }
        $status = [int]$httpResponse.StatusCode
        $text = ''
        try { $text = (New-Object IO.StreamReader($httpResponse.GetResponseStream())).ReadToEnd() } catch {}
        if ($status -eq 401) { throw "401 on $Method $Path : wrong admin password?" }
        throw "$Method $Path failed: HTTP $status $text"
    }

    $content = [Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())
    if ($content) { return $content | ConvertFrom-Json }
}

$people = @(
    @{ slug = 'ana-garcia';           name = 'Ana García';            message = 'Estudiante de DAW. Me encanta el frontend y Vue.' },
    @{ slug = 'pablo-fernandez';      name = 'Pablo Fernández';       message = 'Busco prácticas en backend con Java y Spring Boot.' },
    @{ slug = 'lucia-martinez';       name = 'Lucía Martínez';        message = '' },
    @{ slug = 'javier-lopez-castro';  name = 'Javier López Castro';   message = 'Desarrollador junior, siempre aprendiendo algo nuevo.' },
    @{ slug = 'maria-angeles-fv';     name = 'María de los Ángeles Fernández-Villaverde Rodríguez'; message = 'Diseño UX/UI. Hablemos de accesibilidad y buenas interfaces.' },
    @{ slug = 'diego-perez';          name = 'Diego Pérez';           message = '' },
    @{ slug = 'carmen-sanchez';       name = 'Carmen Sánchez Iglesias'; message = 'De Vigo. Bases de datos, SQL y mucho café.' },
    @{ slug = 'hugo-dominguez';       name = 'Hugo Domínguez';        message = 'Me interesa la ciberseguridad. ¡Hablemos!' },
    # yellow row: a blocked word hidden inside a longer word (the filter lets it through, the dashboard hints)
    @{ slug = 'ivan-rey';             name = 'Iván Rey';              message = 'HelloM1erdaCaca' },
    # PENDING_REVIEW: flagged 3 times, then edits the text -> waits for the moderator's approval
    @{ slug = 'nerea-otero';          name = 'Nerea Otero';           message = 'Hola, soy Nerea.';
       flags = 3; editTo = 'Hola, soy Nerea. Estudio DAM en Vigo.' }
)
# red row: flagged once, not fixed yet
($people | Where-Object { $_.slug -eq 'pablo-fernandez' }).flags = 1
$unopened = @{ slug = 'sara-vazquez' }

function Get-DemoUrl($slug) { "https://www.linkedin.com/in/demo-$slug" }

# ---------- login check (fails fast on a wrong password) ----------
Write-Host "Backend: $BaseUrl"
$null = Invoke-Api GET '/api/admin/stats' -Headers $adminHeaders
Write-Host 'Admin login OK'

if ($Reset) {
    $null = Invoke-Api POST '/api/admin/reset' -Headers $adminHeaders
    Write-Host 'App reset: all attendees wiped'
}

# ---------- attendees: scan, open (keep cookie), save profile ----------
Write-Host ''
Write-Host 'Attendees:'
$skipped = 0
$i = 0
$phones = @{}   # slug -> @{ token; session }, only for people created in this run
foreach ($p in $people) {
    $i++
    $url = Get-DemoUrl $p.slug
    $scan = Invoke-Api POST '/api/attendees/scan' @{ linkedinUrl = $url } -Headers $adminHeaders

    if ($scan.status -ne 'ISSUED') {
        # their page is already open from an earlier run: scanning again only asks the moderator
        Write-Host ("  {0}. {1,-52} already in the app, skipped (run with -Reset to start fresh)" -f $i, $p.name)
        $skipped++
        continue
    }

    $phone = New-Object Microsoft.PowerShell.Commands.WebRequestSession   # this phone's cookie jar
    $null = Invoke-Api GET "/api/attendees/$($scan.token)" -Session $phone
    $saved = Invoke-Api POST "/api/attendees/$($scan.token)/profile" @{ name = $p.name; message = $p.message } -Session $phone
    $phones[$p.slug] = @{ token = $scan.token; session = $phone }

    $intro = if ($p.message) { $p.message } else { '(no intro)' }
    Write-Host ("  {0}. {1,-52} {2}" -f $i, $saved.name, $intro)
}

# ---------- one more person: scanned, page NOT opened ----------
Write-Host ''
$scan = Invoke-Api POST '/api/attendees/scan' @{ linkedinUrl = (Get-DemoUrl $unopened.slug) } -Headers $adminHeaders
if ($scan.status -eq 'ISSUED') {
    Write-Host 'Not opened yet (open this to see the first-visit form):'
    Write-Host "  $FrontendUrl/p/$($scan.token)"
} else {
    Write-Host 'Unopened person: their page was already opened in an earlier run (use -Reset for a fresh link)'
}

# ---------- moderation: flags (and the edit that sends someone to review) ----------
Write-Host ''
Write-Host 'Moderation:'
$all = Invoke-Api GET '/api/admin/attendees' -Headers $adminHeaders
foreach ($p in ($people | Where-Object { $_.flags })) {
    if (-not $phones.ContainsKey($p.slug)) {
        Write-Host "  $($p.name): existed before this run, left as it is"
        continue
    }
    $target = @($all | Where-Object { $_.linkedinUrl -eq (Get-DemoUrl $p.slug) })[0]
    for ($n = 0; $n -lt $p.flags; $n++) {
        $null = Invoke-Api POST "/api/admin/attendees/$($target.adminId)/flag" -Headers $adminHeaders
    }
    if ($p.editTo) {
        $phone = $phones[$p.slug]
        $edited = Invoke-Api POST "/api/attendees/$($phone.token)/profile" @{ name = $p.name; message = $p.editTo } -Session $phone.session
        Write-Host "  $($p.name): flagged $($p.flags)x, then edited -> $($edited.status)"
    } else {
        Write-Host "  $($p.name): flagged $($p.flags)x -> FLAGGED (hidden from the others' lists)"
    }
}

# ---------- what the dashboard shows ----------
$all = Invoke-Api GET '/api/admin/attendees' -Headers $adminHeaders
Write-Host ''
Write-Host 'Dashboard rows:'
foreach ($a in $all) {
    $label = if (-not $a.profileComplete) { if ($a.pageOpened) { 'opened, no name yet' } else { 'not opened yet' } }
             elseif ($a.status -eq 'FLAGGED') { 'FLAGGED (red)' }
             elseif ($a.status -eq 'PENDING_REVIEW') { 'PENDING_REVIEW (waits for approval)' }
             elseif ($a.possibleHiddenWord) { 'possible hidden word (yellow)' }
             else { 'visible' }
    $shown = if ($a.name) { $a.name } else { $a.linkedinUrl }
    Write-Host ("  {0,-52} {1}" -f $shown, $label)
}

# ---------- summary ----------
$stats = Invoke-Api GET '/api/admin/stats' -Headers $adminHeaders
Write-Host ''
Write-Host ("Stats: joined {0}, completed {1}, flagged {2}, pending review {3}" -f `
    $stats.joined, $stats.completed, $stats.flagged, $stats.pendingReview)
if ($skipped -gt 0) { Write-Host "($skipped attendee(s) skipped because they already existed)" }
