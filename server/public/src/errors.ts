import messages from "../../../contract/errors.json";

export type ErrorCode = keyof typeof messages;

const NETWORK_MESSAGE = "تعذر الاتصال بالخادم. تحقق من الشبكة وأعد المحاولة.";

/** A refusal from the api (or the dashboard's own checks), with the contract's Arabic message. */
export class ApiFailure extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly status = 0,
  ) {
    super(message);
    this.name = "ApiFailure";
  }
}

export function failure(code: ErrorCode, status = 0): ApiFailure {
  return new ApiFailure(code, messages[code], status);
}

export function networkFailure(): ApiFailure {
  return new ApiFailure("network", NETWORK_MESSAGE);
}

export const messageOf = (error: unknown): string =>
  error instanceof Error && error.message ? error.message : messages.temporary_service_failure;
