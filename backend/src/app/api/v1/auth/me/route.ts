import { NextRequest, NextResponse } from 'next/server';

export async function GET(req: NextRequest) {
  const sessionCookie = req.cookies.get('omniface_session')?.value;
  if (!sessionCookie) {
    return NextResponse.json({ authenticated: false, error: 'Unauthorized' }, { status: 401 });
  }

  return NextResponse.json({
    authenticated: true,
    user: {
      id: 'usr_admin_01',
      email: 'admin@omniface.ai',
      fullName: 'Fleet Administrator',
      role: 'SUPERADMIN',
      orgName: 'National Institute of Technology',
      tier: 'BUSINESS',
    },
  });
}
