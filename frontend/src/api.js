// Small helper around fetch() for talking to the Spring backend.
// Every backend error comes back as JSON {"error": "..."} (already translated),
// except Spring Security's 401, which has an empty body.

export class ApiError extends Error {
  constructor(status, message, fields, reason) {
    super(message)
    this.status = status // 400, 401, 403, 404, 409, 410, 422, 500...
    this.fields = fields // per-field validation messages, when status is 400
    this.reason = reason // why a page is gone (410): 'session.replaced' | 'event.ended'
  }
}

export async function api(path, { method = 'GET', body } = {}) {
  const response = await fetch(path, {
    method,
    credentials: 'same-origin', // send our cookies (wristband / staff login)
    headers: {
      // tells Spring Security this is an app request: on 401 it answers plainly
      // instead of making the browser pop up its own username/password box
      'X-Requested-With': 'XMLHttpRequest',
      ...(body ? { 'Content-Type': 'application/json' } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  })

  if (response.status === 204) return null // done, nothing to return

  const text = await response.text()
  const data = text ? JSON.parse(text) : null

  if (!response.ok) {
    throw new ApiError(response.status, data?.error ?? 'Error', data?.fields, data?.reason)
  }
  return data
}
