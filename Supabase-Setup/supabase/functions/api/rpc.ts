import { ApiError, fromDatabaseError } from "./errors.ts";

export type RpcResult = { data: unknown; error: unknown };

export interface RpcClient {
  rpc(fn: string, args?: Record<string, unknown>): PromiseLike<RpcResult>;
}

export const unavailable: RpcClient = {
  rpc: () =>
    Promise.resolve({
      data: null,
      error: new ApiError("temporary_service_failure"),
    }),
};

/** One routine call; a contract code raised by the routine becomes its ApiError. */
export async function call<T>(
  client: RpcClient,
  fn: string,
  args: Record<string, unknown> = {},
): Promise<T> {
  const { data, error } = await client.rpc(fn, args);
  if (error) {
    if (error instanceof ApiError) throw error;
    const message = (error as { message?: unknown }).message;
    throw fromDatabaseError(error) ?? new Error(`${fn}: ${String(message)}`);
  }
  return data as T;
}
