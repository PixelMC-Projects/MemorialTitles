import React from 'react';

export function defaultHead(id) {
  try {
    const value = BigInt('0x'+id.replaceAll('-',''));
    const hash = Number(BigInt.asIntN(32,value^(value>>32n)^(value>>64n)^(value>>96n)));
    return ((hash%18)+18)%18;
  } catch { return 6; }
}
export function PlayerAvatar({ id, version = 0, name, large = false }) {
  return <img className={`player-avatar ${large ? 'large-avatar' : ''}`} src={'/api/avatars/'+id+'.png?v='+version} alt="" aria-hidden="true" title={name+'的头像'} width={large ? 36 : 32} height={large ? 36 : 32} onError={event => { event.currentTarget.onerror = null; event.currentTarget.src='/default-heads/'+defaultHead(id)+'.png'; }}/>
}
