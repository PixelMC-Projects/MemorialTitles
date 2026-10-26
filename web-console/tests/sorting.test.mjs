import test from 'node:test';
import assert from 'node:assert/strict';
import {sortTitleRows} from '../src/sorting.js';

const title=(id,name,extra={})=>({id,name:{segments:[{text:name}]},issuable:true,visible:true,...extra});
test('archive names sort naturally across page boundaries in both directions',()=>{
  const rows=Array.from({length:45},(_,i)=>title('id'+i,'Title'+(45-i)));
  assert.equal(sortTitleRows(rows,{key:'name',direction:'asc'}).slice(20,40)[0].name.segments[0].text,'Title21');
  assert.equal(sortTitleRows(rows,{key:'name',direction:'desc'}).slice(20,40)[0].name.segments[0].text,'Title25');
});
test('holder counts are numeric and equal values keep alphabetical names',()=>{
  const rows=[title('c','Charlie'),title('b','Bravo'),title('a','Alpha')],counts={a:2,b:10,c:2};
  assert.deepEqual(sortTitleRows(rows,{key:'holders',direction:'asc'},counts).map(x=>x.id),['a','c','b']);
  assert.deepEqual(sortTitleRows(rows,{key:'holders',direction:'desc'},counts).map(x=>x.id),['b','a','c']);
});
test('unknown archive values stay last in either direction and status groups keep names ascending',()=>{
  const rows=[title('b','Bravo',{createdAt:20,source:'B',issuable:false}),title('a','Alpha',{createdAt:10,source:'A',issuable:false}),title('c','Charlie',{createdAt:0,source:'',issuable:true})];
  for(const direction of ['asc','desc']){
    assert.equal(sortTitleRows(rows,{key:'created',direction}).at(-1).id,'c');
    assert.equal(sortTitleRows(rows,{key:'source',direction}).at(-1).id,'c');
  }
  assert.deepEqual(sortTitleRows(rows,{key:'retired',direction:'desc'}).map(x=>x.id),['a','b','c']);
});
