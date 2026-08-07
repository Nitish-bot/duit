import { onRequest } from "firebase-functions/v2/https";

export const emulatorReady = onRequest((_request, response) => {
  response.status(200).json({
    service: "duit-functions-emulator",
    status: "ok",
  });
});
