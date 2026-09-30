package io.github.flowerjvm.factory.infrastructure.incidentapplication.tool;

/** Consumer-invoked, bounded local demo only. It is not executed by Factory production or release. */
final class IncidentApplicationLauncherSource {
    private IncidentApplicationLauncherSource() { }
    /** Fixed HTTP ingress over Docker exec stdio; the product retains a network-none namespace. */
    private static final String LOCAL_RELAY = """
            using System;
            using System.IO;
            using System.Net;
            using System.Text;
            using System.Diagnostics;
            using System.Threading;
            using System.Threading.Tasks;
            using System.Text.RegularExpressions;
            public static class IncidentLocalRelay {
                private const int MaxInput = 65536, MaxBody = 1048576, MaxHeaders = 16384;
                public static void Handle(HttpListenerContext context, string docker, string container, int port) {
                    HandleAsync(context, docker, container, port).GetAwaiter().GetResult();
                }
                private static async Task<byte[]> ReadBounded(Stream stream, int maximum, CancellationToken token) {
                    using (var output = new MemoryStream()) {
                        var buffer = new byte[8192];
                        while (true) {
                            int count = await stream.ReadAsync(buffer, 0, Math.Min(buffer.Length, maximum + 1 - (int)output.Length), token);
                            if (count == 0) return output.ToArray();
                            output.Write(buffer, 0, count);
                            if (output.Length > maximum) throw new InvalidDataException("LOCAL_RELAY_BOUND");
                        }
                    }
                }
                private static async Task Send(HttpListenerResponse response, int status, byte[] body, string type, CancellationToken token) {
                    response.StatusCode = status; response.ContentType = type; response.ContentLength64 = body.Length;
                    response.Headers["X-Content-Type-Options"] = "nosniff";
                    response.Headers["Cache-Control"] = "no-store";
                    response.Headers["Referrer-Policy"] = "no-referrer";
                    response.Headers["Content-Security-Policy"] = "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'";
                    await response.OutputStream.WriteAsync(body, 0, body.Length, token);
                }
                private static Task Error(HttpListenerResponse response, int status, string code, CancellationToken token) {
                    return Send(response, status, Encoding.UTF8.GetBytes("{\\"errorCode\\":\\"" + code + "\\"}"), "application/json; charset=utf-8", token);
                }
                private static async Task HandleAsync(HttpListenerContext context, string docker, string container, int port) {
                    using (var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5))) {
                        var token = timeout.Token; Process process = null;
                        try {
                            var request = context.Request; string expectedHost = "127.0.0.1:" + port;
                            string origin = request.Headers["Origin"], path = request.RawUrl ?? "";
                            if (request.Headers["Host"] != expectedHost || (origin != null && origin != "http://" + expectedHost)) {
                                await Error(context.Response, 403, "ORIGIN_NOT_ALLOWED", token); return;
                            }
                            if (path.Length > 256 || path.Contains("?") || path.Contains("%") || path.Contains("\\\\")) {
                                await Error(context.Response, 400, "INVALID_PATH", token); return;
                            }
                            string method = request.HttpMethod;
                            bool investigate = path == "/api/investigations";
                            bool readPath = path == "/" || path == "/app.js" || path == "/app.css" || path == "/api/config"
                                || Regex.IsMatch(path, "^/api/history(?:/[0-9a-f]{64}(?:/report)?)?$");
                            if ((investigate && method != "POST") || (!investigate && method != "GET")) {
                                await Error(context.Response, 405, "METHOD_NOT_ALLOWED", token); return;
                            }
                            if (!investigate && !readPath) { await Error(context.Response, 404, "NOT_FOUND", token); return; }
                            if (request.ContentLength64 > MaxInput) { await Error(context.Response, 413, "REQUEST_TOO_LARGE", token); return; }
                            string type = request.ContentType;
                            if (investigate && (type == null || !Regex.IsMatch(type, "(?i)^application/json(?:\\\\s*;\\\\s*charset=utf-8)?$"))) {
                                await Error(context.Response, 415, "JSON_CONTENT_TYPE_REQUIRED", token); return;
                            }
                            string encoding = request.Headers["Content-Encoding"];
                            if (encoding != null && !encoding.Equals("identity", StringComparison.OrdinalIgnoreCase)) {
                                await Error(context.Response, 415, "CONTENT_ENCODING_NOT_SUPPORTED", token); return;
                            }
                            byte[] input;
                            try { input = await ReadBounded(request.InputStream, MaxInput, token); }
                            catch (InvalidDataException) { await Error(context.Response, 413, "REQUEST_TOO_LARGE", token); return; }
                            if (!investigate && input.Length != 0) { await Error(context.Response, 400, "GET_BODY_NOT_SUPPORTED", token); return; }
                            var start = new ProcessStartInfo(docker) { UseShellExecute = false, CreateNoWindow = true,
                                RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true };
                            foreach (string item in new [] { "exec", "-i", container, "/usr/bin/curl", "--disable", "--silent", "--show-error",
                                "--http1.1", "--include", "--proto", "=http", "--noproxy", "*", "--connect-timeout", "1", "--max-time", "2",
                                "--max-filesize", "1048576", "--max-redirs", "0", "--request", method, "--header", "Host: " + expectedHost,
                                "--header", "Expect:", "--url", "http://127.0.0.1:8080" + path }) start.ArgumentList.Add(item);
                            if (investigate) { start.ArgumentList.Add("--header"); start.ArgumentList.Add("Content-Type: application/json; charset=utf-8");
                                start.ArgumentList.Add("--data-binary"); start.ArgumentList.Add("@-"); }
                            process = new Process { StartInfo = start };
                            if (!process.Start()) throw new IOException("LOCAL_RELAY_START_FAILED");
                            var output = ReadBounded(process.StandardOutput.BaseStream, MaxBody + MaxHeaders, token);
                            var errors = ReadBounded(process.StandardError.BaseStream, 8192, token);
                            await process.StandardInput.BaseStream.WriteAsync(input, 0, input.Length, token); process.StandardInput.Close();
                            await Task.WhenAll(output, errors, process.WaitForExitAsync(token));
                            if (process.ExitCode != 0) throw new IOException("LOCAL_RELAY_UPSTREAM_FAILED");
                            byte[] wire = await output; int boundary = -1;
                            for (int i = 0; i + 3 < wire.Length && i < MaxHeaders; i++) {
                                if (wire[i] == 13 && wire[i+1] == 10 && wire[i+2] == 13 && wire[i+3] == 10) { boundary = i; break; }
                            }
                            if (boundary < 0 || wire.Length - boundary - 4 > MaxBody) throw new IOException("LOCAL_RELAY_RESPONSE_INVALID");
                            string headers = Encoding.ASCII.GetString(wire, 0, boundary); string[] lines = headers.Split(new [] { "\\r\\n" }, StringSplitOptions.None);
                            var status = Regex.Match(lines[0], "^HTTP/1\\\\.1 ([0-9]{3})(?: .*)?$");
                            if (!status.Success || lines.Length > 64) throw new IOException("LOCAL_RELAY_RESPONSE_INVALID");
                            int code = int.Parse(status.Groups[1].Value); if (code < 200 || code > 599) throw new IOException("LOCAL_RELAY_RESPONSE_INVALID");
                            string contentType = null, disposition = null; long length = -1;
                            foreach (string line in lines) {
                                int colon = line.IndexOf(':'); if (colon < 1) continue;
                                string key = line.Substring(0, colon), value = line.Substring(colon + 1).Trim();
                                if (key.Equals("Content-Type", StringComparison.OrdinalIgnoreCase)) contentType = value;
                                if (key.Equals("Content-Length", StringComparison.OrdinalIgnoreCase)) length = long.Parse(value);
                                if (key.Equals("Content-Disposition", StringComparison.OrdinalIgnoreCase)) disposition = value;
                            }
                            if (length != wire.Length - boundary - 4 || contentType == null
                                || !Regex.IsMatch(contentType, "(?i)^(application/json|text/(html|javascript|css|markdown)); charset=utf-8$"))
                                throw new IOException("LOCAL_RELAY_RESPONSE_INVALID");
                            if (disposition != null && Regex.IsMatch(disposition, "^attachment; filename=\\"investigation-[0-9a-f]{64}\\\\.md\\"$"))
                                context.Response.Headers["Content-Disposition"] = disposition;
                            byte[] body = new byte[(int)length]; Buffer.BlockCopy(wire, boundary + 4, body, 0, body.Length);
                            await Send(context.Response, code, body, contentType, token);
                        } catch (Exception) {
                            using (var failureTimeout = new CancellationTokenSource(TimeSpan.FromSeconds(1))) {
                                try { await Error(context.Response, 502, "LOCAL_RELAY_UNAVAILABLE", failureTimeout.Token); } catch (Exception) { }
                            }
                        } finally {
                            try { context.Response.Close(); } catch (Exception) { }
                            if (process != null) { try { if (!process.HasExited) process.Kill(true); } catch (Exception) { } process.Dispose(); }
                        }
                    }
                }
            }
            """;
    static final String POWERSHELL = """
            #Requires -Version 7.2
            [CmdletBinding()]
            param([ValidateRange(1024,65535)][int] $Port = 18080,
                  [ValidateRange(5,3600)][int] $MaxSeconds = 900)
            Set-StrictMode -Version Latest
            $ErrorActionPreference = 'Stop'
            $demoRoot = [IO.Path]::GetFullPath($PSScriptRoot)
            $demoImage = 'maven@sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e'
            $demoOwner = [Guid]::NewGuid().ToString('N')
            $demoName = 'incident-local-' + $demoOwner
            $demoLabel = 'io.github.flowerjvm.incident-local.owner'
            $demoDocker = (Get-Command docker -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
            Add-Type -TypeDefinition @'
            @@LOCAL_RELAY_CS@@
            '@
            function Assert-DemoPath([string] $Path) {
                if ($Path.Contains(',') -or $Path -match '[\\x00-\\x1f\\x7f]') { throw 'UNSAFE_DEMO_PATH' }
                $demoCursor = [IO.Path]::GetFullPath($Path)
                while ($demoCursor) {
                    if (Test-Path -LiteralPath $demoCursor) {
                        $demoItem = Get-Item -LiteralPath $demoCursor -Force
                        if (($demoItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or $demoItem.LinkType) { throw 'UNSAFE_DEMO_LINK' }
                    }
                    $demoCursor = [IO.Path]::GetDirectoryName($demoCursor)
                }
            }
            function Invoke-DemoDocker([string[]] $Arguments, [switch] $AllowFailure) {
                $demoStart = [Diagnostics.ProcessStartInfo]::new($demoDocker)
                $demoStart.UseShellExecute = $false; $demoStart.CreateNoWindow = $true
                $demoStart.RedirectStandardOutput = $true; $demoStart.RedirectStandardError = $true
                foreach ($demoArgument in $Arguments) { $demoStart.ArgumentList.Add($demoArgument) }
                $demoProcess = [Diagnostics.Process]::new(); $demoProcess.StartInfo = $demoStart
                try {
                    if (!$demoProcess.Start()) { throw 'DEMO_DOCKER_START_FAILED' }
                    $demoStdout = $demoProcess.StandardOutput.ReadToEndAsync()
                    $demoStderr = $demoProcess.StandardError.ReadToEndAsync()
                    if (!$demoProcess.WaitForExit(15000)) { $demoProcess.Kill($true); throw 'DEMO_DOCKER_TIMEOUT' }
                    $demoOutput = $demoStdout.GetAwaiter().GetResult().Trim()
                    $demoError = $demoStderr.GetAwaiter().GetResult()
                    if ($demoOutput.Length + $demoError.Length -gt 65536) { throw 'DEMO_DOCKER_OUTPUT_BOUND' }
                    if ($demoProcess.ExitCode -ne 0 -and !$AllowFailure) { throw 'DEMO_DOCKER_FAILED' }
                    return @{ ExitCode = $demoProcess.ExitCode; Output = $demoOutput }
                } finally { $demoProcess.Dispose() }
            }
            Assert-DemoPath $demoRoot
            foreach ($demoEntry in (Get-ChildItem -LiteralPath $demoRoot -Force)) {
                if ($demoEntry.Name -cnotin @('app.jar','BOM.json','product-config.json','source-lock.json','README.md','run-local.ps1','lib','incident-data')) { throw 'UNEXPECTED_BUNDLE_MEMBER' }
                Assert-DemoPath $demoEntry.FullName
            }
            foreach ($demoRequired in @('BOM.json','product-config.json','app.jar','lib')) { Assert-DemoPath (Join-Path $demoRoot $demoRequired) }
            $demoBom = Get-Content -LiteralPath (Join-Path $demoRoot 'BOM.json') -Raw -Encoding utf8 | ConvertFrom-Json
            $demoConfig = Get-Content -LiteralPath (Join-Path $demoRoot 'product-config.json') -Raw -Encoding utf8 | ConvertFrom-Json
            if ($demoBom.schemaVersion -cne 'factory.incident-application.bill-of-materials.v1' -or
                $demoConfig.variant -cnotin @('BASIC','HISTORY') -or $demoBom.order.variant -cne $demoConfig.variant -or
                $demoConfig.historyEnabled -ne ($demoConfig.variant -ceq 'HISTORY')) { throw 'INVALID_PRODUCT_CONFIGURATION' }
            if ((Get-FileHash -LiteralPath (Join-Path $demoRoot 'app.jar') -Algorithm SHA256).Hash.ToLowerInvariant() -cne $demoBom.appJarSha256) { throw 'PRODUCT_JAR_HASH_MISMATCH' }
            if (@($demoBom.runtimeLibraries).Count -ne 3) { throw 'INVALID_PRODUCT_LIBRARIES' }
            if (@(Get-ChildItem -LiteralPath (Join-Path $demoRoot 'lib') -Force).Count -ne 3) { throw 'UNEXPECTED_LIBRARY_MEMBER' }
            foreach ($demoLibrary in $demoBom.runtimeLibraries) {
                if ($demoLibrary.path -cnotmatch '^lib/jackson-(annotations-2\\.21|core-2\\.21\\.4|databind-2\\.21\\.4)\\.jar$') { throw 'INVALID_LIBRARY_PATH' }
                $demoLibraryPath = Join-Path $demoRoot $demoLibrary.path; Assert-DemoPath $demoLibraryPath
                if ((Get-FileHash -LiteralPath $demoLibraryPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $demoLibrary.sha256) { throw 'PRODUCT_LIBRARY_HASH_MISMATCH' }
            }
            $null = Invoke-DemoDocker @('image','inspect','--format','{{.Os}}/{{.Architecture}}',$demoImage)
            $demoData = Join-Path $demoRoot 'incident-data'
            if ($demoConfig.variant -ceq 'HISTORY') {
                Assert-DemoPath $demoData
                if (!(Test-Path -LiteralPath $demoData)) { $null = New-Item -ItemType Directory -Path $demoData }
                if (!(Test-Path -LiteralPath $demoData -PathType Container)) { throw 'INVALID_HISTORY_DIRECTORY' }
                Assert-DemoPath $demoData
            }
            $demoListener = $null
            try {
                $demoArguments = @('create','--pull=never','--init','--name',$demoName,'--label',($demoLabel + '=' + $demoOwner),
                    '--label',('io.github.flowerjvm.incident-local.port=' + $Port),
                    '--network','none',
                    '--read-only','--user','1000:1000','--cap-drop','ALL','--security-opt','no-new-privileges',
                    '--pids-limit','128','--memory','512m','--memory-swap','512m','--cpus','2',
                    '--log-driver','local','--log-opt','max-size=1m','--log-opt','max-file=1','--log-opt','compress=false',
                    '--tmpfs','/tmp:rw,nosuid,nodev,noexec,size=64m,mode=1777',
                    '--mount',('type=bind,source=' + $demoRoot + ',target=/product,readonly'),
                    '--env','PORT=8080','--env','INCIDENT_DATA_DIR=/data','--entrypoint','/opt/java/openjdk/bin/java')
                if ($demoConfig.variant -ceq 'HISTORY') { $demoArguments += @('--mount',('type=bind,source=' + $demoData + ',target=/data')) }
                else { $demoArguments += @('--tmpfs','/data:rw,nosuid,nodev,noexec,size=1m,mode=1777') }
                $demoArguments += @($demoImage,'-Xmx256m','-cp','/product/app.jar:/product/lib/*','io.github.flowerjvm.product.incident.IncidentApplication')
                $null = Invoke-DemoDocker $demoArguments
                $null = Invoke-DemoDocker @('start',$demoName)
                $demoMode = Invoke-DemoDocker @('inspect','--format','{{.HostConfig.NetworkMode}}',$demoName)
                if ($demoMode.Output -cne 'none') { throw 'PRODUCT_NETWORK_ISOLATION_MISMATCH' }
                $null = Invoke-DemoDocker @('exec',$demoName,'/usr/bin/curl','--disable','--silent','--show-error','--fail','--noproxy','*',
                    '--proto','=http','--connect-timeout','1','--max-time','2','--retry','5','--retry-connrefused','--retry-delay','0',
                    '--retry-max-time','8','http://127.0.0.1:8080/api/config')
                $demoListener = [Net.HttpListener]::new()
                $demoListener.Prefixes.Add('http://127.0.0.1:' + $Port + '/')
                $demoListener.Start()
                Write-Host ('Local demo: http://127.0.0.1:' + $Port + '  (Ctrl+C to stop; max ' + $MaxSeconds + ' seconds)')
                $demoUntil = [DateTimeOffset]::UtcNow.AddSeconds($MaxSeconds)
                $demoPending = $demoListener.GetContextAsync()
                $demoNextCheck = [DateTimeOffset]::UtcNow
                while ([DateTimeOffset]::UtcNow -lt $demoUntil) {
                    if ($demoPending.Wait(100)) {
                        $demoContext = $demoPending.GetAwaiter().GetResult()
                        [IncidentLocalRelay]::Handle($demoContext, $demoDocker, $demoName, $Port)
                        $demoPending = $demoListener.GetContextAsync()
                    }
                    if ([DateTimeOffset]::UtcNow -ge $demoNextCheck) {
                        $demoState = Invoke-DemoDocker @('inspect','--format','{{.State.Running}}',$demoName)
                        if ($demoState.Output -cne 'true') { throw 'LOCAL_PRODUCT_STOPPED' }
                        $demoNextCheck = [DateTimeOffset]::UtcNow.AddMilliseconds(500)
                    }
                }
            } finally {
                if ($null -ne $demoListener) { $demoListener.Close() }
                $demoContainerState = Invoke-DemoDocker @('ps','-a','--filter',('name=^/' + $demoName + '$'),'--format','{{.Names}}')
                if ($demoContainerState.Output) {
                    $demoOwnedContainer = Invoke-DemoDocker @('ps','-a','--filter',('name=^/' + $demoName + '$'),'--filter',('label=' + $demoLabel + '=' + $demoOwner),'--format','{{.Names}}')
                    if ($demoOwnedContainer.Output -cne $demoName) { throw 'DEMO_CONTAINER_OWNER_MISMATCH' }
                    $null = Invoke-DemoDocker @('stop','--time','5',$demoName)
                    $null = Invoke-DemoDocker @('rm',$demoName)
                }
            }
            Write-Host 'Local demo stopped. HISTORY data, if any, was preserved.'
            """.replace("@@LOCAL_RELAY_CS@@", LOCAL_RELAY);
}
