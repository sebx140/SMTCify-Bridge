using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Windows.Media.Control;

internal static class Program
{
    private const string Prefix = "http://127.0.0.1:17643/";
    private const string TokenHeader = "X-SMTCify-Token";

    private static string _token = "";

    private static async Task Main()
    {
        var dataDirectory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "SMTCify");

        Directory.CreateDirectory(dataDirectory);

        var tokenPath = Path.Combine(dataDirectory, "bridge-token.txt");
        _token = Convert.ToHexString(RandomNumberGenerator.GetBytes(32));

        await File.WriteAllTextAsync(tokenPath, _token, Encoding.UTF8);

        using var listener = new HttpListener();
        listener.Prefixes.Add(Prefix);

        try
        {
            listener.Start();
        }
        catch (HttpListenerException ex)
        {
            Console.Error.WriteLine($"No se pudo iniciar HTTP: {ex.Message}");
            Environment.ExitCode = 1;
            return;
        }

        Console.WriteLine("[SMTCify Windows] API iniciada.");
        Console.WriteLine($"Dirección: {Prefix}");
        Console.WriteLine($"Token guardado en: {tokenPath}");
        Console.WriteLine("Presiona Ctrl+C para detener el servicio.");

        using var cancellation = new CancellationTokenSource();

        Console.CancelKeyPress += (_, e) =>
        {
            e.Cancel = true;
            cancellation.Cancel();
            listener.Stop();
        };

        while (!cancellation.IsCancellationRequested && listener.IsListening)
        {
            HttpListenerContext context;

            try
            {
                context = await listener.GetContextAsync();
            }
            catch (HttpListenerException)
            {
                break;
            }
            catch (ObjectDisposedException)
            {
                break;
            }

            _ = HandleRequestAsync(context);
        }
    }

    private static async Task HandleRequestAsync(HttpListenerContext context)
    {
        try
        {
            var request = context.Request;
            var path = request.Url?.AbsolutePath ?? "/";

            context.Response.Headers["Cache-Control"] = "no-store";
            context.Response.Headers["X-Content-Type-Options"] = "nosniff";

            if (path == "/status" && request.HttpMethod == "GET")
            {
                await WriteJsonAsync(
                    context.Response, 200, await GetStatusAsync());
                return;
            }

            if (path.StartsWith("/command/", StringComparison.Ordinal))
            {
                if (request.HttpMethod != "POST")
                {
                    await WriteJsonAsync(context.Response, 405,
                        new { error = "method_not_allowed" });
                    return;
                }

                var suppliedToken = request.Headers[TokenHeader];

                if (!TokenMatches(suppliedToken))
                {
                    await WriteJsonAsync(context.Response, 401,
                        new { error = "unauthorized" });
                    return;
                }

                var result = await ExecuteCommandAsync(
                    path["/command/".Length..]);

                await WriteJsonAsync(context.Response,
                    result.Success ? 200 : 400, result);
                return;
            }

            await WriteJsonAsync(context.Response, 404,
                new { error = "not_found" });
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"Error procesando solicitud: {ex.Message}");

            try
            {
                await WriteJsonAsync(context.Response, 500,
                    new { error = "internal_error" });
            }
            catch
            {
                context.Response.Abort();
            }
        }
    }

    private static bool TokenMatches(string? suppliedToken)
    {
        if (string.IsNullOrEmpty(suppliedToken))
            return false;

        var supplied = Encoding.UTF8.GetBytes(suppliedToken);
        var expected = Encoding.UTF8.GetBytes(_token);

        return CryptographicOperations.FixedTimeEquals(supplied, expected);
    }

    private static async Task<object> GetStatusAsync()
    {
        var manager =
            await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();

        var session = manager.GetCurrentSession();

        if (session is null)
        {
            return new
            {
                available = false,
                message = "No hay una sesión multimedia activa."
            };
        }

        var media = await session.TryGetMediaPropertiesAsync();
        var playback = session.GetPlaybackInfo();
        var timeline = session.GetTimelineProperties();

        return new
        {
            available = true,
            source = session.SourceAppUserModelId,
            title = media.Title,
            artist = media.Artist,
            album = media.AlbumTitle,
            status = playback.PlaybackStatus.ToString(),
            positionSeconds = timeline.Position.TotalSeconds,
            durationSeconds = timeline.EndTime.TotalSeconds,
            controls = new
            {
                canPlay = playback.Controls.IsPlayEnabled,
                canPause = playback.Controls.IsPauseEnabled,
                canNext = playback.Controls.IsNextEnabled,
                canPrevious = playback.Controls.IsPreviousEnabled
            }
        };
    }

    private static async Task<CommandResult> ExecuteCommandAsync(string command)
    {
        var manager =
            await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();

        var session = manager.GetCurrentSession();

        if (session is null)
            return new(false, "No hay una sesión multimedia activa.");

        var success = command.ToLowerInvariant() switch
        {
            "play" => await session.TryPlayAsync(),
            "pause" => await session.TryPauseAsync(),
            "toggle" => await session.TryTogglePlayPauseAsync(),
            "next" => await session.TrySkipNextAsync(),
            "previous" => await session.TrySkipPreviousAsync(),
            _ => (bool?)null
        };

        if (success is null)
            return new(false, "Comando desconocido.");

        return new(success.Value, success.Value
            ? "Comando enviado."
            : "La sesión no aceptó el comando.");
    }

    private static async Task WriteJsonAsync(
        HttpListenerResponse response, int statusCode, object data)
    {
        response.StatusCode = statusCode;
        response.ContentType = "application/json; charset=utf-8";

        var json = JsonSerializer.Serialize(data,
            new JsonSerializerOptions { WriteIndented = true });

        var bytes = Encoding.UTF8.GetBytes(json);
        response.ContentLength64 = bytes.Length;

        await response.OutputStream.WriteAsync(bytes);
        response.Close();
    }

    private sealed record CommandResult(bool Success, string Message);
}
