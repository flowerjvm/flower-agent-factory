using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Win32.SafeHandles;

namespace Flower.MaintenanceDemo
{
    // This helper never starts a shell, writes an output file, or manages a container.
    // The caller supplies literal ArgumentList elements and handles its own container.
    public sealed class ProcessResult
    {
        public int ExitCode { get; set; }
        public byte[] Stdout { get; set; }
        public byte[] Stderr { get; set; }
        public bool TimedOut { get; set; }
        public bool OutputLimitExceeded { get; set; }
    }

    public static class DemoProcess
    {
        public static ProcessResult Run(string executable, string[] arguments, byte[] input,
            int timeoutSeconds, int stdoutLimit, int stderrLimit)
        {
            return RunAsync(executable, arguments, input, timeoutSeconds, stdoutLimit, stderrLimit)
                .GetAwaiter().GetResult();
        }

        private static async Task<ProcessResult> RunAsync(string executable, string[] arguments,
            byte[] input, int timeoutSeconds, int stdoutLimit, int stderrLimit)
        {
            if (timeoutSeconds < 1 || timeoutSeconds > 180 || stdoutLimit < 1 ||
                stdoutLimit > 4194304 || stderrLimit < 1 || stderrLimit > 262144 ||
                input.Length > 262144) throw new ArgumentException("PROCESS_BOUNDS");
            using var process = new Process();
            process.StartInfo.FileName = executable;
            process.StartInfo.UseShellExecute = false;
            process.StartInfo.CreateNoWindow = true;
            process.StartInfo.RedirectStandardInput = true;
            process.StartInfo.RedirectStandardOutput = true;
            process.StartInfo.RedirectStandardError = true;
            foreach (var argument in arguments) process.StartInfo.ArgumentList.Add(argument);
            // These are not forwarded into the container. Avoid accidental debug/trace files
            // written by CLI plugins or injected Java settings inherited from the parent.
            process.StartInfo.Environment.Remove("JAVA_TOOL_OPTIONS");
            process.StartInfo.Environment.Remove("JDK_JAVA_OPTIONS");
            process.StartInfo.Environment.Remove("_JAVA_OPTIONS");
            using var cancellation = new CancellationTokenSource(TimeSpan.FromSeconds(timeoutSeconds));
            int exceeded = 0;
            process.Start();
            var stdout = Capture(process.StandardOutput.BaseStream, stdoutLimit, cancellation,
                () => Interlocked.Exchange(ref exceeded, 1));
            var stderr = Capture(process.StandardError.BaseStream, stderrLimit, cancellation,
                () => Interlocked.Exchange(ref exceeded, 1));
            var stdin = Send(process.StandardInput.BaseStream, input, cancellation.Token);
            bool stopped = false;
            try
            {
                await process.WaitForExitAsync(cancellation.Token).ConfigureAwait(false);
                await Task.WhenAll(stdin, stdout, stderr).WaitAsync(cancellation.Token)
                    .ConfigureAwait(false);
            }
            catch (OperationCanceledException) { stopped = true; }
            catch (IOException) { stopped = !process.HasExited; }
            finally
            {
                if (stopped || !process.HasExited)
                {
                    try { process.Kill(true); } catch (InvalidOperationException) { }
                    // The Docker CLI is not the container. The caller separately reconciles
                    // and removes only its exact name/ownership-label match.
                    process.WaitForExit(3000);
                }
            }
            // Pump tasks are cancellation-bounded; never wait indefinitely for a pipe held
            // open by an unrelated descendant. Their incomplete content is never success.
            return new ProcessResult {
                ExitCode = process.HasExited ? process.ExitCode : -1,
                Stdout = stdout.IsCompletedSuccessfully ? stdout.Result : Array.Empty<byte>(),
                Stderr = stderr.IsCompletedSuccessfully ? stderr.Result : Array.Empty<byte>(),
                TimedOut = stopped && exceeded == 0,
                OutputLimitExceeded = exceeded != 0
            };
        }

        private static async Task Send(Stream stream, byte[] bytes, CancellationToken token)
        {
            try { await stream.WriteAsync(bytes, 0, bytes.Length, token).ConfigureAwait(false); }
            catch (IOException) { /* A process may reject input before consuming all bytes. */ }
            finally { stream.Dispose(); }
        }

        private static async Task<byte[]> Capture(Stream source, int maximum,
            CancellationTokenSource cancellation, Action onLimit)
        {
            using var destination = new MemoryStream();
            var buffer = new byte[4096];
            try
            {
                int count;
                while ((count = await source.ReadAsync(buffer, 0, buffer.Length,
                    cancellation.Token).ConfigureAwait(false)) != 0)
                {
                    if (destination.Length + count > maximum)
                    {
                        onLimit();
                        cancellation.Cancel();
                        return Array.Empty<byte>();
                    }
                    destination.Write(buffer, 0, count);
                }
            }
            catch (OperationCanceledException) { return Array.Empty<byte>(); }
            catch (IOException) { return Array.Empty<byte>(); }
            return destination.ToArray();
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct FileInformation
        {
            public uint Attributes;
            public System.Runtime.InteropServices.ComTypes.FILETIME Creation;
            public System.Runtime.InteropServices.ComTypes.FILETIME LastAccess;
            public System.Runtime.InteropServices.ComTypes.FILETIME LastWrite;
            public uint VolumeSerial;
            public uint SizeHigh;
            public uint SizeLow;
            public uint Links;
            public uint IndexHigh;
            public uint IndexLow;
        }

        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool GetFileInformationByHandle(SafeFileHandle handle,
            out FileInformation information);

        public static byte[] ReadRegularFile(string path, int maximum)
        {
            // FileShare.Read prevents in-place writers during the bounded read. Ancestor
            // reparse checks and post-read checks remain the PowerShell caller's job.
            using var file = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
            if (!GetFileInformationByHandle(file.SafeFileHandle, out var information) ||
                information.Links != 1 || (information.Attributes & 0x410) != 0)
                throw new IOException("REGULAR_SINGLE_LINK_FILE_REQUIRED");
            if (file.Length < 0 || file.Length > maximum) throw new IOException("FILE_SIZE_LIMIT");
            var bytes = new byte[checked((int)file.Length)];
            int offset = 0;
            while (offset < bytes.Length)
            {
                int count = file.Read(bytes, offset, bytes.Length - offset);
                if (count == 0) throw new IOException("FILE_CHANGED_DURING_READ");
                offset += count;
            }
            if (file.ReadByte() != -1) throw new IOException("FILE_CHANGED_DURING_READ");
            return bytes;
        }
    }
}
