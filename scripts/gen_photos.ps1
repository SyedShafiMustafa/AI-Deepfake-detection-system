# gen_photos.ps1 — generates 3 distinct sample photos for the emulator gallery.
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File gen_photos.ps1 -OutDir <dir>

param([Parameter(Mandatory = $true)][string]$OutDir)

Add-Type -AssemblyName System.Drawing

$colors = @(
  @(108,  99, 255),   # purple  (matches the app's primary color)
  @(0,   245, 160),   # green   (matches the app's "REAL" color)
  @(255,  71,  87)    # red     (matches the app's "FAKE" color)
)
$labels = @('SAMPLE 1', 'SAMPLE 2', 'SAMPLE 3')

if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir | Out-Null }

for ($i = 0; $i -lt 3; $i++) {
  $bmp  = New-Object System.Drawing.Bitmap 1024, 768
  $g    = [System.Drawing.Graphics]::FromImage($bmp)
  $c    = $colors[$i]

  $g.Clear([System.Drawing.Color]::FromArgb(255, $c[0], $c[1], $c[2]))

  # Label so each photo is recognizable
  $font  = New-Object System.Drawing.Font('Arial', 72, [System.Drawing.FontStyle]::Bold)
  $g.DrawString($labels[$i], $font, [System.Drawing.Brushes]::White, 40, 40)

  # Simple face so the photos look like portraits
  $g.FillEllipse([System.Drawing.Brushes]::White, 412, 230, 200, 200)
  $g.FillEllipse([System.Drawing.Brushes]::Black, 462, 292, 24, 30)
  $g.FillEllipse([System.Drawing.Brushes]::Black, 540, 292, 24, 30)
  $pen = New-Object System.Drawing.Pen([System.Drawing.Color]::White, 10)
  $g.DrawArc($pen, 445, 325, 135, 90, 20, 140)

  $path = Join-Path $OutDir ("sample{0}.png" -f ($i + 1))
  $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)

  $g.Dispose()
  $bmp.Dispose()
  Write-Host "Generated $path"
}